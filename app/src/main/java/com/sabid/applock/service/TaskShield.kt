package com.sabid.applock.service

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.sabid.applock.AppLockApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps a locked app's contents out of the recents / overview screen.
 *
 * The leak this closes is real and was measured, not theorised. When a protected app is
 * challenged the lock screen goes *over* it, so the app underneath is still a live, resumed,
 * fully drawn task. Android snapshots a task when it stops being visible, and that snapshot
 * is what the overview screen renders — so a locked calendar showed its whole month, and a
 * locked finance app showed its balances, to anyone who never got past the PIN.
 *
 * **Timing is the entire problem.** Four things were verified on a real device:
 *
 *  - Emptying the task with a back press *while the app is still the top task* leaves nothing
 *    to snapshot, and the overview screen falls back to a blank placeholder card. This works.
 *  - Doing anything at all *after* the user has pressed home is too late. The snapshot was
 *    already captured at that transition, with content.
 *  - `killBackgroundProcesses` does **not** discard an already-captured snapshot. The card
 *    still showed the app's contents afterwards.
 *  - Pressing back at an app that is still *cold-starting* achieves nothing — the key lands
 *    on a starting window with no activity behind it to finish — and can leave the launch
 *    half-finished. That is what made the first open of a locked app look like a crash while
 *    the second worked, and it is why [arm] now waits for the launch to settle first.
 *
 * So there are two moments at which a task can be emptied, and this class owns both:
 *
 *  - [arm], the moment we challenge, while our own window covers the app. This is the cheap
 *    one and it covers every app the user never got into.
 *  - [scrubOnLeave], after the user has legitimately unlocked an app, used it, and left. The
 *    snapshot has already been taken by then, so the app has to be brought back in front —
 *    behind [ScreenCover] — and emptied there. That costs a blank screen for a moment, which
 *    is why it is a setting the user can turn off.
 *
 * [evict] remains for the declined path, because [arm] is a no-op when the user has recents
 * protection off or when there is no accessibility service to press back with.
 *
 * Nothing here can edit another app's recents entry directly: `FLAG_SECURE` only applies to
 * windows we own, task snapshots belong to system_server, and `REMOVE_TASKS` is
 * signature-level. Emptying the task is the only lever a normal app has.
 */
object TaskShield {

    /** Long enough for a finish animation to be under way before the next action lands. */
    private const val SETTLE_MS = 180L

    /**
     * Cap on injected back presses.
     *
     * One is enough for the case that actually leaks — an app opened fresh from the launcher,
     * a single activity in its task. More are attempted because a deep task needs unwinding,
     * but the count is bounded and the loop stops the instant something that is not the
     * target reaches the foreground, so a back press can never be delivered to the launcher
     * or to an unrelated app.
     */
    private const val BACK_ATTEMPTS = 3

    private const val HOME_ATTEMPTS = 3

    /** How long after an unlock-time relaunch the app's own transition is ignored. */
    private const val REOPEN_GRACE_MS = 3_000L

    /**
     * How long after a shield action the launcher coming forward is still *our* doing.
     *
     * Emptying the app's task makes the launcher visible, which looks exactly like the user
     * pressing home — and [LockEngine] takes the lock screen down on a genuine app switch, so
     * without this the shield would dismiss the very lock screen it was protecting.
     */
    private const val GRACE_MS = 1_200L

    /** How still the window stream has to be before the target counts as launched. */
    private const val READY_QUIET_MS = 220L

    /** Never press back sooner than this after being asked to shield. */
    private const val READY_MIN_WAIT_MS = 120L

    /**
     * Longest we wait for a launch to settle before pressing back anyway.
     *
     * An app that never stops changing its windows — a game, something with an interstitial —
     * would otherwise hold the shield off forever, and the lock screen is already covering it
     * while we wait, so waiting costs the user nothing but a delay before the app's task is
     * emptied.
     */
    private const val READY_MAX_WAIT_MS = 900L

    /**
     * How long to wait after a back press for the app to actually go away.
     *
     * The signal is an accessibility window change, which arrives well after the key has been
     * handled, so without this wait the loop reads a stale foreground package and presses back
     * again at an app that has already gone — and those extra presses land on the launcher,
     * where they close whatever folder the user had open. Short, and left the moment the app
     * departs, so a deep task still gets its remaining presses promptly.
     */
    private const val DEPART_WAIT_MS = 420L

    /**
     * How far back the usage-stats departure check looks.
     *
     * Only the *latest* activity event is used, so a wide window costs nothing in accuracy —
     * an event from before the app launched can never outrank the app's own resume — and a
     * narrow one risks finding no event at all on a ROM that batches them.
     */
    private const val USAGE_LOOKBACK_MS = 3_000L

    /** How long to wait for [ScreenCover] to actually paint before fronting anything. */
    private const val COVER_DRAW_TIMEOUT_MS = 600L

    /**
     * Extra pause after the cover's first frame, before the app is fronted.
     *
     * The frame callback says our process has drawn; the window becoming *visible* as far as
     * the activity manager's background-start rules are concerned happens slightly later, and
     * that verdict is the difference between the app coming forward and the request being
     * aborted outright. See [ScreenCover.awaitDrawn].
     */
    private const val COVER_SETTLE_MS = 140L

    /** How long a single attempt at fronting the app gets before the flags are varied. */
    private const val FRONT_ATTEMPT_MS = 900L

    /**
     * Consecutive failures to front a departed app before this stops being attempted.
     *
     * Some ROMs refuse background activity starts outright, whatever we hold and whatever is
     * visible. On those, every departure from a locked app would blank the screen for a second
     * and achieve nothing, which is worse than the leak. Counted in memory rather than
     * persisted: a fresh process tries again, which is the right way round for a restriction
     * the user may have just lifted in vendor settings.
     */
    private const val FRONT_FAILURES_ALLOWED = 3

    /**
     * How long to wait before believing the user really has left the app.
     *
     * "The launcher came forward" is not the same as "the user went home". Launching an app on
     * this ROM raises a launcher window part-way through the transition, which arrives here as a
     * departure from the app that is in the middle of opening — and acting on that closed an app
     * half a second after it was tapped. Waiting and then re-asking which app is actually
     * resumed tells the two apart: in the transient case the app itself is back on top by now.
     */
    private const val CONFIRM_LEAVE_MS = 450L

    /**
     * Minimum gap between two scrubs of the same package.
     *
     * This is the loop breaker, and it has to exist: [scrubOnLeave] fronts the app itself, so
     * its own back presses produce exactly the "user left a locked app" transition that
     * triggered it. Without the cooldown that is an infinite relaunch loop.
     */
    private const val SCRUB_COOLDOWN_MS = 5_000L

    @Volatile
    private var busyPackage: String? = null

    @Volatile
    private var busyUntil = 0L

    @Volatile
    private var scrubbedPackage: String? = null

    @Volatile
    private var scrubbedAt = 0L

    /** Consecutive [scrub] attempts that could not get the app back in front. */
    @Volatile
    private var frontFailures = 0

    /**
     * Packages whose task we pressed back at, and which therefore have to be relaunched if
     * the user goes on to unlock successfully.
     *
     * Membership is "we interfered with this app", *not* "we saw its task empty". Those came
     * apart on cold starts: back presses at an app that was still launching left it with no
     * usable window, the task did not report itself as closed, and so nothing relaunched it —
     * the user unlocked, the lock screen came down, and behind it was a dead app. That is the
     * "first open crashes, second open works" report. If we touched it, we put it back.
     */
    private val disturbed = ConcurrentHashMap.newKeySet<String>()

    /**
     * Packages whose task we watched go empty.
     *
     * Narrower than [disturbed] and used for the opposite purpose: to know that there is
     * nothing left to close, so [evict] must not press back at whatever replaced it and
     * [scrubOnLeave] has no work to do.
     */
    private val closedTasks = ConcurrentHashMap.newKeySet<String>()

    /**
     * Whether the launcher (or anything else) appearing right now is a side effect of
     * shielding [pkg] rather than the user navigating away.
     *
     * Self-expiring on purpose. An explicitly cleared flag has to be cleared on every exit
     * path including the failures, and the one time it leaked would be a lock screen that
     * could not be dismissed.
     */
    fun isShielding(pkg: String): Boolean =
        busyPackage == pkg && SystemClock.elapsedRealtime() < busyUntil

    /**
     * Closes [pkg]'s task while the lock screen covers it, so no snapshot of its contents can
     * ever be taken.
     *
     * Must be called with a lock window already on screen **and not yet focusable**. Both
     * halves matter: the window covers the app so nothing is briefly readable, and it has to
     * be non-focusable or the injected back press lands on our own lock screen — which
     * [LockOverlay.BackAwareRoot] reads as the user giving up. [LockEngine] calls
     * [LockOverlay.grantFocus] the moment this returns.
     *
     * Suspends until done, which is a few hundred milliseconds — up to about a second and a
     * half on a cold start, because the first thing it does is wait for the launch to finish.
     * The caller is already on a background coroutine, the lock screen is up and takes touches
     * throughout, and the alternative — pressing back at a half-started app — is the bug this
     * wait exists to avoid.
     */
    suspend fun arm(context: Context, pkg: String) {
        if (pkg in LockEngine.exemptions(context)) return
        if (!AppLockApp.get().snapshot.hideFromRecents) return
        if (!AppLockAccessibilityService.isRunning) {
            LockLog.w("cannot shield $pkg from recents: no accessibility service to press back")
            return
        }

        // The lock window going away means the challenge is over — unlocked, declined, or
        // replaced. Everything below would then be pressing back at an app the user is
        // entitled to be using, so every wait and every attempt re-checks it.
        val challengeUp = { LockOverlay.isFor(pkg) }

        hold(pkg)
        val waited = awaitLaunchSettled(pkg, challengeUp)

        val backs = emptyTask(context, pkg, challengeUp)

        // Covers the launcher transition that follows the final back press.
        hold(pkg)

        val top = LockSession.foregroundPackage
        val emptied = backs > 0 && taskLooksEmpty(context, pkg)

        // Assigned both ways, not just added. An entry left over from an earlier challenge
        // that ended without either exit running would otherwise make the next successful
        // unlock relaunch an app that is still running, throwing away whatever screen the
        // user was actually on.
        if (backs > 0) disturbed.add(pkg) else disturbed.remove(pkg)
        if (emptied) closedTasks.add(pkg) else closedTasks.remove(pkg)
        LockLog.d("shielded $pkg (waited=${waited}ms backs=$backs closed=$emptied top=$top)")
    }

    /**
     * Waits for [pkg] to stop putting windows up, and reports how long that took.
     *
     * The signal is quiescence of the accessibility window stream — see
     * [AppLockAccessibilityService.quietForMs] — because this service does not request window
     * content and so cannot ask what the top window is. It is enough: a cold start is a burst
     * of window changes, and the end of the burst is the app being ready to handle a back
     * press. Bounded by [READY_MAX_WAIT_MS] so a busy app cannot stall the shield.
     */
    private suspend fun awaitLaunchSettled(pkg: String, active: () -> Boolean): Long {
        val start = SystemClock.elapsedRealtime()
        while (true) {
            val waited = SystemClock.elapsedRealtime() - start
            if (!active() || waited >= READY_MAX_WAIT_MS) return waited
            if (waited >= READY_MIN_WAIT_MS &&
                AppLockAccessibilityService.quietForMs() >= READY_QUIET_MS
            ) {
                return waited
            }
            hold(pkg)
            delay(60)
        }
    }

    /**
     * Presses back at [pkg] until its task is empty, and reports how many presses that took.
     *
     * Shared by both moments a task can be emptied — [arm] behind the lock screen and [scrub]
     * behind the cover — because the two had drifted into slightly different loops over the
     * same three constraints: never more than [BACK_ATTEMPTS] presses, never a press once
     * something else is in front, and never a press after [active] says the reason for doing
     * this has gone away.
     *
     * Each press is followed by a wait for the app to actually depart rather than a flat delay,
     * so the common case — one activity, one press — stops after one press instead of sending
     * two more into the launcher behind us.
     */
    private suspend fun emptyTask(context: Context, pkg: String, active: () -> Boolean): Int {
        var backs = 0
        while (backs < BACK_ATTEMPTS && active() && stillOnTarget(context, pkg)) {
            hold(pkg)
            if (!AppLockAccessibilityService.pressBack()) break
            backs++
            if (awaitDeparture(context, pkg)) break
        }
        return backs
    }

    /**
     * Waits up to [DEPART_WAIT_MS] for something other than [pkg] to be in front, and reports
     * whether that happened.
     *
     * Two signals, because the obvious one is blind at exactly the wrong moment. The
     * accessibility stream reports *our* package as the foreground window as soon as the lock
     * window or the cover goes up, and it stays that way while the app's task collapses behind
     * it — so `stillOnTarget` cannot see the launcher arrive, and the loop spent its whole
     * budget of back presses every time, with the surplus landing on the launcher. Usage-stats
     * activity events are not affected: an overlay is not an activity, so the most recent
     * ACTIVITY_RESUMED is a real app, and it changes to the launcher precisely when the task
     * empties. Returns false when that permission is missing, which just restores the old
     * behaviour.
     *
     * Our own package does not count as departure either way: adding or removing our windows
     * produces foreground changes of its own, and treating one as "the app is gone" would stop
     * the back presses with the app's task still standing.
     */
    private suspend fun awaitDeparture(context: Context, pkg: String): Boolean {
        var waited = 0L
        while (waited < DEPART_WAIT_MS) {
            delay(60)
            waited += 60
            hold(pkg)
            if (!stillOnTarget(context, pkg)) return true

            // Every other pass: this is an IPC and a linear scan of the event window, which is
            // not something to do at 60ms intervals for no benefit.
            if (waited % 120 == 0L && resumedElsewhere(context, pkg)) return true
        }
        return false
    }

    /** Whether the most recently resumed activity belongs to neither [pkg] nor us. */
    private fun resumedElsewhere(context: Context, pkg: String): Boolean {
        val top = UsageWatchdog.currentForeground(context, USAGE_LOOKBACK_MS) ?: return false
        return top != pkg && top != context.packageName
    }

    /**
     * Whether [pkg]'s task appears to have been emptied — the verdict the rest of this class
     * acts on, since a task that is gone must not be pressed back at again and needs no scrub.
     *
     * Asks both detectors for the same reason [awaitDeparture] does: while our window is up the
     * accessibility stream reports us as the foreground, so on its own it can neither confirm
     * nor deny this. Reading our own package as "emptied" is what it used to do, and that was a
     * guess that happened to be right often enough to hide the times it wasn't.
     */
    private fun taskLooksEmpty(context: Context, pkg: String): Boolean {
        if (resumedElsewhere(context, pkg)) return true
        val top = LockSession.foregroundPackage ?: return false
        return top != pkg && top != context.packageName
    }

    /**
     * Whether the target is still the app we would be pressing back on.
     *
     * Our own package counts as "still on target". Adding the lock window makes us the
     * foreground window immediately, so requiring `foregroundPackage == pkg` would end the
     * loop before the first back press and shield nothing at all. Anything else in the
     * foreground — the launcher, another app — ends the loop, which is what keeps injected
     * back presses from ever reaching something we did not mean to close.
     */
    private fun stillOnTarget(context: Context, pkg: String): Boolean {
        // Asked first, and only ever used to *keep* the loop going. SystemUI raises a window of
        // its own part-way through a launch — a heads-up notification, the gesture handle, the
        // transition itself — and the window stream reports that as the foreground, which used
        // to end the loop before it had pressed anything: `backs=0`, the task left standing,
        // and the app's contents readable in the switcher after all. An activity-level answer
        // is not fooled by that, because none of those things is an activity. Where it is
        // unavailable or stale it changes nothing.
        if (UsageWatchdog.currentForeground(context, USAGE_LOOKBACK_MS) == pkg) return true

        val top = LockSession.foregroundPackage ?: return true
        return top == pkg || top == context.packageName
    }

    private fun hold(pkg: String, ms: Long = GRACE_MS) {
        val until = SystemClock.elapsedRealtime() + ms
        busyUntil = if (busyPackage == pkg) maxOf(busyUntil, until) else until
        busyPackage = pkg
    }

    /**
     * Brings [pkg] back after a successful unlock, but only if the shield actually interfered
     * with it.
     *
     * This is the cost of shielding: the app was finished, so letting the user in means
     * starting it again rather than simply uncovering it. The app opens at its own launcher
     * entry point instead of wherever it was, which is the trade for its contents never
     * having been snapshotable.
     *
     * Keyed on [disturbed] rather than on "we saw the task go empty" deliberately. A back
     * press that did not visibly empty the task may still have destroyed the app's only
     * activity, or the ROM may have killed the process on the way out, and in that case doing
     * nothing here leaves the user staring at whatever is behind our window — which they
     * reasonably read as a crash. Relaunching an app that turns out to be alive and untouched
     * just brings its existing task forward, which is harmless.
     */
    fun reopen(context: Context, pkg: String) {
        closedTasks.remove(pkg)
        if (!disturbed.remove(pkg)) return
        val intent = runCatching { context.packageManager.getLaunchIntentForPackage(pkg) }
            .getOrNull() ?: return
        // Relaunching raises the launcher for a moment mid-transition, which reads exactly like
        // the user leaving the app. Without this, that blip aged the fresh grant away (so the
        // next open asked for the PIN again) and, with no usage access to contradict it, made
        // [scrub] close the app we had just reopened: "unlocks, then the app closes".
        hold(pkg, REOPEN_GRACE_MS)
        runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { LockLog.w("could not reopen $pkg after unlock: ${it.message}") }
    }

    /** Forgets any pending reopen for [pkg]. */
    fun forget(pkg: String) {
        disturbed.remove(pkg)
        closedTasks.remove(pkg)
    }

    /**
     * Evicts [pkg] after a challenge the user did not pass.
     *
     * Still needed alongside [arm]: shielding is skipped when the user has turned it off and
     * when no accessibility service is available, and a deep task may have survived the
     * bounded back presses.
     *
     * Pass `allowBack = false` when the app is no longer the top task — the user pressed home
     * while the lock screen was up, say. A back press then would be delivered to whatever is
     * in front now, which is somebody's launcher, and closing a folder they had open is a
     * visible glitch we get nothing for.
     *
     * With `allowBack = true` the caller must have taken the lock window down first: this
     * injects a back press, and a focusable lock window would receive it itself and loop
     * straight back into the give-up handler.
     */
    fun evict(context: Context, pkg: String, allowBack: Boolean = true) {
        // Marked busy here rather than only inside the coroutine, so that everything reading
        // isShielding — including [scrubOnLeave], which would otherwise race this and drag the
        // app the user just declined back onto the screen — sees it immediately.
        hold(pkg)
        AppLockApp.get().appScope.launch { run(context, pkg, allowBack) }
    }

    private suspend fun run(context: Context, pkg: String, allowBack: Boolean) {
        // Never turn this on the launcher or SystemUI. Backing out of home does nothing, but
        // killing it is a visible and pointless glitch, and it is reachable through a
        // mis-detected foreground package.
        if (pkg in LockEngine.exemptions(context)) return

        val hideFromRecents = AppLockApp.get().snapshot.hideFromRecents

        // Whatever happens here, the app is not being handed back to the user, so a pending
        // reopen would be wrong.
        disturbed.remove(pkg)

        // Already emptied by arm(), so there is no activity of theirs left to finish and a
        // back press now would be delivered to whatever replaced it.
        val alreadyClosed = closedTasks.remove(pkg)

        hold(pkg)
        var left: Boolean
        val backed = if (hideFromRecents && allowBack && !alreadyClosed) {
            AppLockAccessibilityService.pressBack()
        } else {
            false
        }
        if (backed) delay(SETTLE_MS)

        // Home is sent even when back reported success: back may have popped one activity of
        // several, or the app may have handled it by moving its own task to the back.
        var attempt = 0
        do {
            hold(pkg)
            withContext(Dispatchers.Main) { goHome(context) }
            delay(SETTLE_MS * 2)
            left = LockSession.foregroundPackage != pkg
            attempt++
        } while (!left && attempt < HOME_ATTEMPTS)

        val killed = if (hideFromRecents) kill(context, pkg) else false
        LockLog.d("evicted $pkg (back=$backed home=$attempt left=$left kill=$killed)")

        // Fail closed. If the app is somehow still in front — the ROM refused our home
        // actions, or the user relaunched it while we were working — then a protected app is
        // on screen unlocked, which is the one state this app exists to prevent.
        if (LockSession.foregroundPackage == pkg) {
            LockLog.w("$pkg is still in the foreground after eviction; challenging again")
            LockEngine.recheck(context, pkg)
        }
    }

    /**
     * Closes [pkg] again after the user has finished with it, so the app switcher keeps no
     * picture of what they were doing.
     *
     * This is the half of recents protection [arm] cannot reach. An app the user unlocked and
     * used is a normal, resumed app; when they press home, system_server captures its contents
     * for the overview screen at that transition, and by the time this class hears about it the
     * picture exists. Killing the process does not delete it — that was measured. Emptying the
     * task does, and emptying a task means pressing back at it while it is in front.
     *
     * So the sequence is: cover the screen, bring the app back for a moment behind the cover,
     * empty it, uncover. The user sees a black screen for a few hundred milliseconds after
     * leaving a locked app. That is the whole cost, it only happens when
     * `closeLockedOnLeave` is on, and the setting says so in as many words.
     *
     * Bails out rather than improvising whenever the situation stops matching what it was
     * called for — no overlay grant, the user launched something else, the app never came
     * forward. Every exit takes the cover down.
     */
    fun scrubOnLeave(context: Context, pkg: String) {
        AppLockApp.get().appScope.launch { scrub(context, pkg) }
    }

    private suspend fun scrub(context: Context, pkg: String) {
        if (pkg in LockEngine.exemptions(context)) return
        if (!AppLockAccessibilityService.isRunning) return

        // We are the reason this app stopped being in front — a challenge was declined, or the
        // lock screen was dismissed with home. It is being got rid of already; bringing it back
        // to close it again would undo that in the most visible way possible.
        if (isShielding(pkg)) return

        // Nothing was left behind to hide: the shield already emptied this task, or a scrub
        // moments ago did. The cooldown is also what stops this recursing on its own back
        // presses — see SCRUB_COOLDOWN_MS.
        if (pkg in closedTasks) return
        if (pkg == scrubbedPackage && SystemClock.elapsedRealtime() - scrubbedAt < SCRUB_COOLDOWN_MS) {
            return
        }
        // Learned, not assumed: some ROMs refuse to front another app from the background under
        // any circumstances, and on those this can only blank the screen for a second and fail.
        if (frontFailures >= FRONT_FAILURES_ALLOWED) return

        scrubbedPackage = pkg
        scrubbedAt = SystemClock.elapsedRealtime()

        if (runCatching { context.packageManager.getLaunchIntentForPackage(pkg) }.getOrNull() == null) {
            return
        }

        // Let the transition finish and then ask again, from a source that reports activities
        // rather than windows. An app the user is still in is the most recently resumed activity
        // even while a launcher window is briefly on top of it, and closing that app would be
        // the most obnoxious bug this feature could have. A null answer means no usage access,
        // in which case the window signal is all there is and we go on.
        delay(CONFIRM_LEAVE_MS)
        val resumed = UsageWatchdog.currentForeground(context, USAGE_LOOKBACK_MS)
        if (resumed == pkg || LockSession.foregroundPackage == pkg || isShielding(pkg)) {
            LockLog.d("not closing $pkg after all: the user is still in it")
            return
        }

        hold(pkg)
        if (!withContext(Dispatchers.Main) { ScreenCover.show(context) }) {
            LockLog.w("not closing $pkg on leave: nothing to cover the screen with")
            return
        }

        try {
            // Both halves are load-bearing. Nothing may be fronted until the cover is genuinely
            // painted, or the app's contents flash on screen — and the activity manager will not
            // honour a background start until it can see a visible window of ours either.
            hold(pkg)
            if (!ScreenCover.awaitDrawn(COVER_DRAW_TIMEOUT_MS)) {
                LockLog.w("not closing $pkg on leave: the cover never painted")
                return
            }
            hold(pkg)
            delay(COVER_SETTLE_MS)

            if (!front(context, pkg)) {
                frontFailures++
                LockLog.w("gave up closing $pkg on leave: it never came forward")
                if (frontFailures >= FRONT_FAILURES_ALLOWED) {
                    LockLog.w("this ROM refuses background starts; not closing locked apps on leave")
                    ProtectionAlert.recentsUncovered(context)
                }
                return
            }
            frontFailures = 0

            awaitLaunchSettled(pkg) { true }
            val backs = emptyTask(context, pkg) { true }

            hold(pkg)
            val emptied = taskLooksEmpty(context, pkg)
            if (emptied) closedTasks.add(pkg) else AppLockAccessibilityService.pushHome()

            // A scrubbed app is never reopened for the user: they had already left it.
            disturbed.remove(pkg)
            val killed = kill(context, pkg)
            LockLog.d("closed $pkg after leaving it (backs=$backs closed=$emptied kill=$killed)")
        } finally {
            hold(pkg)
            withContext(Dispatchers.Main) { ScreenCover.hide() }
        }
    }

    /**
     * Brings [pkg] to the front so its task can be emptied, and reports whether it got there.
     *
     * `startActivity` tells us nothing: a refused background start returns normally and simply
     * does not happen, so the only honest answer comes from watching the foreground. Two
     * attempts, with different flags, because the quiet refusal is not always about permission —
     * a no-animation start into a task the ROM considers finished can also be dropped, and
     * asking for the task to be reset instead goes through where the first one did not.
     *
     * Deliberately not louder than this. Failing here means one app's recents card keeps its
     * picture; carrying on regardless would mean fronting an app with nothing hiding it.
     */
    private suspend fun front(context: Context, pkg: String): Boolean {
        val flagSets = listOf(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION,
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        )

        for ((attempt, flags) in flagSets.withIndex()) {
            val intent = runCatching { context.packageManager.getLaunchIntentForPackage(pkg) }
                .getOrNull()?.addFlags(flags) ?: return false

            hold(pkg)
            val started = runCatching { context.startActivity(intent) }
                .onFailure { LockLog.w("could not front $pkg to close it: ${it.message}") }
                .isSuccess
            if (!started) return false

            var waited = 0L
            while (waited < FRONT_ATTEMPT_MS) {
                delay(60)
                waited += 60
                hold(pkg)
                if (LockSession.foregroundPackage == pkg) return true
            }
            LockLog.w("front attempt ${attempt + 1} for $pkg was refused; trying other flags")
        }
        return false
    }

    private fun goHome(context: Context) {
        if (AppLockAccessibilityService.pushHome()) return
        // No live accessibility service to ask, so do it the long way round. This is the only
        // route left when the ROM has killed detection and usage-stats polling is carrying
        // the lock on its own.
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
        }
    }

    /**
     * Drops the app's process. Requires KILL_BACKGROUND_PROCESSES, a normal permission granted
     * at install — but it is only advisory: the system ignores it for foreground and
     * persistent processes, and some ROMs ignore it more broadly. It also does **not** discard
     * a snapshot that has already been captured, which is why [arm] exists.
     */
    private fun kill(context: Context, pkg: String): Boolean = runCatching {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return false
        manager.killBackgroundProcesses(pkg)
        true
    }.getOrDefault(false)
}
