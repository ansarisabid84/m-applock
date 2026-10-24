package com.sabid.applock.service

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.sabid.applock.AppLockApp
import com.sabid.applock.core.model.ChallengeReason
import com.sabid.applock.core.model.LockDecision
import com.sabid.applock.core.util.Permissions
import com.sabid.applock.core.util.UninstallGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The one place that decides what happens when the foreground app changes.
 *
 * Both detectors feed this: [AppLockAccessibilityService] (fast, event-driven, the main
 * path) and [UsageWatchdog] (slow polling backstop for ROMs that kill accessibility
 * services). Keeping the logic here means the two can never drift apart, and it is why
 * both running at once is harmless — they converge on the same state.
 */
object LockEngine {

    @Volatile
    private var exempt: Set<String> = emptySet()

    @Volatile
    private var homeScreens: Set<String> = emptySet()

    /**
     * Packages we must never put a lock screen in front of.
     *
     * Challenging the launcher or SystemUI soft-bricks the device: the lock screen covers
     * home, and dismissing it returns to home, which raises it again.
     */
    fun exemptions(context: Context): Set<String> {
        exempt.takeIf { it.isNotEmpty() }?.let { return it }
        val resolved = buildSet {
            add(context.packageName)
            add("com.android.systemui")
            addAll(launchers(context))
        }
        exempt = resolved
        return resolved
    }

    /**
     * Drops the cached exemption set so the next read re-queries.
     *
     * Called when a package is installed or removed, because the set contains every app that
     * answers CATEGORY_HOME and that list changes when the user installs a launcher. Cached
     * for the life of the process, a launcher installed after AppLock started would not be
     * exempt — and challenging the home screen is the one failure that soft-bricks the phone:
     * the lock screen covers home, dismissing it returns to home, which raises it again.
     */
    fun invalidateExemptions() {
        exempt = emptySet()
        homeScreens = emptySet()
    }

    /**
     * Whether [pkg] is the user's home screen or the system's overview UI — that is, whether
     * arriving here means they left the app they were in rather than switched to another one.
     *
     * Used to decide when a locked app has to be closed behind them
     * ([TaskShield.scrubOnLeave]). Switching straight from a locked app into another app is
     * deliberately not treated as leaving: the recents snapshot is the thing being protected,
     * and doing that work while the user is busy in a different app would blank their screen
     * for no benefit they can see.
     */
    fun isHomeScreen(context: Context, pkg: String): Boolean {
        if (pkg == "com.android.systemui") return true
        homeScreens.takeIf { it.isNotEmpty() }?.let { return pkg in it }
        val resolved = launchers(context)
        if (resolved.isNotEmpty()) homeScreens = resolved
        return pkg in resolved
    }

    private fun launchers(context: Context): Set<String> = runCatching {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        context.packageManager.queryIntentActivities(home, 0)
            .mapNotNull { it.activityInfo?.packageName }
            .toSet()
    }.getOrDefault(emptySet())

    /**
     * Records [pkg] as the foreground app and challenges it if it is protected.
     *
     * Safe to call repeatedly with the same package; only a genuine change does work.
     */
    suspend fun onForeground(context: Context, pkg: String) {
        val previous = LockSession.foregroundPackage

        // Record the new foreground package for *every* window change, including the
        // launcher and system UI. Skipping the update for those left the previous
        // protected package latched here, so app -> home -> same app took the
        // `previous == pkg` early return and was never re-evaluated: a full bypass.
        LockSession.foregroundPackage = pkg

        // A WindowManager overlay is our own window, not an activity: nothing in the
        // system takes it down when the user presses home or switches apps. Left up, it
        // would sit on top of the launcher looking like a crash.
        //
        // Checked *before* the "has the foreground actually changed?" guard below, and that
        // ordering is the fix for a stuck lock screen. Once the shield has emptied the
        // protected app's task, the launcher is already recorded as the foreground package —
        // so a home press repeats the package we have and would be swallowed by the guard,
        // which is exactly the case where the lock screen was left floating over the home
        // screen.
        //
        // The shield check is what keeps this from firing on our own doing. Closing the
        // protected app's task makes the launcher visible, and a launcher is launchable, so
        // this would tear down the lock screen the shield had just put up — handing over an
        // unlocked app to whoever pressed home.
        val guarded = LockOverlay.target
        if (guarded != null && guarded != pkg &&
            isRealAppSwitch(context, pkg) && !TaskShield.isShielding(guarded)
        ) {
            withContext(Dispatchers.Main) {
                LockOverlay.leaveForHome(context, "foreground moved to $pkg")
            }
        }

        if (previous == pkg) return

        if (previous != null) onLeft(context, previous, pkg)

        // A protected app being pushed out after a declined challenge can twitch back into
        // the foreground for a frame or two. Treating that as a fresh launch put a second
        // lock screen up over the launcher, seconds after the user had already walked away.
        if (TaskShield.isShielding(pkg)) return

        evaluate(context, pkg)
    }

    /**
     * Bookkeeping for the app the user has just navigated away from.
     *
     * Two things happen here. The grant is aged according to the user's relock policy, and —
     * if the app was locked and the user went home rather than into another app — the app is
     * closed behind them so the overview screen keeps no picture of it. See
     * [TaskShield.scrubOnLeave] for why that cannot be done any more cheaply than by closing
     * the app, and [com.sabid.applock.core.data.AppSettings.closeLockedOnLeave] for the switch
     * that turns it off.
     */
    private suspend fun onLeft(context: Context, previous: String, destination: String) {
        // Our own relaunch after an unlock bounces through the launcher; that is not the user
        // leaving, and treating it as one dropped the grant and closed the app they just opened.
        if (TaskShield.isShielding(previous)) return
        val s = AppLockApp.get().settings.current()
        LockSession.onLeftApp(previous, s.relockPolicy, s.briefExitMinutes)

        // Protection off means the user has switched the lock off wholesale. Closing their apps
        // behind them and blanking the screen to do it, while nothing is being locked at all, is
        // all cost and no protection.
        if (!s.protectionEnabled) return
        if (!s.hideFromRecents || !s.closeLockedOnLeave) return
        if (!isHomeScreen(context, destination)) return
        if (previous in exemptions(context)) return
        if (previous !in AppLockApp.get().repo.effectiveLockedPackages()) return

        LockLog.d("$previous was left for $destination")
        TaskShield.scrubOnLeave(context, previous)
    }

    /**
     * Evaluates [pkg] without the "has the foreground actually changed?" guard in
     * [onForeground].
     *
     * [TaskShield] needs this: it re-checks the package it just tried to evict, and at that
     * point [LockSession.foregroundPackage] already *is* that package, so [onForeground] would
     * take its early return and silently leave a protected app on screen unlocked.
     */
    suspend fun recheck(context: Context, pkg: String) {
        evaluate(context, pkg)
    }

    private suspend fun evaluate(context: Context, pkg: String) {
        if (pkg in exemptions(context)) return

        // Already covered, so there is nothing to decide. Adding the overlay reports *our*
        // package as the foreground app, which makes the protected app's next window event
        // look like a fresh launch and land back here — three times per launch on MIUI.
        // [LockOverlay.show] is idempotent so nothing ever flickered, but re-deciding cost a
        // database round-trip and a main-thread hop each time. Deliberately keyed on the live
        // window rather than on `LockSession.challenging`, which can latch stale.
        if (LockOverlay.isFor(pkg)) return

        val decision = AppLockApp.get().repo.decide(pkg, LockSession.isOpen(pkg))
        if (decision !is LockDecision.Challenge) return
        if (!shouldChallenge(context, decision)) return
        present(context, decision.packageName)
    }

    /**
     * Last check before a challenge, and it exists to stop AppLock locking the user out of
     * the phone's own Settings.
     *
     * The tamper guard deliberately covers `com.android.settings`, because that is where the
     * device admin is deactivated and where AppLock can be force-stopped. But Settings is
     * also the only place the "display over other apps" grant can be given — the grant that
     * the lock screen needs in order to appear at all. Without this check, revoking that
     * grant would put the phone in a state where every attempt to open Settings gets bounced
     * to the launcher by [contain] and the user can never get back in to fix it. Nothing the
     * user could do from inside AppLock would help, because AppLock cannot grant it either.
     *
     * The user's own locked apps are never skipped here. Failing open on those would be
     * failing at the one job the app has.
     */
    private fun shouldChallenge(context: Context, decision: LockDecision.Challenge): Boolean {
        if (decision.reason != ChallengeReason.TAMPER_GUARD) return true
        if (Permissions.hasOverlay(context)) return true
        LockLog.w("skipping tamper guard for ${decision.packageName}: no overlay grant to fix")
        return false
    }

    /**
     * Gets a lock screen in front of [pkg], by whichever of the two routes this device
     * actually permits.
     *
     * There are two, and the difference is the whole reason this method is more than one
     * line. An **activity** ([LockPresenter]) is the nicer of the two — it hosts
     * BiometricPrompt, it is a normal window, the system manages its lifetime — but
     * starting one from the background is refused outright on many devices, and refused
     * *silently*: `startActivity` returns success and no window appears. An **overlay**
     * ([LockOverlay]) needs no background-start exemption and so cannot be suppressed,
     * but it cannot host biometrics.
     *
     * So: prefer the activity, verify it actually arrived, and fall through to the overlay
     * when it didn't. Whether it didn't is remembered — the fallback costs
     * [LockPresenter.APPEAR_DEADLINE_MS] with the protected app on screen, and paying that
     * on every single launch of every protected app is not acceptable once we already
     * know the answer.
     */
    private suspend fun present(context: Context, pkg: String) {
        LockLog.d("challenge $pkg")

        val settings = AppLockApp.get().settings.current()

        // Two reasons to skip straight to the overlay. The learned one is that this ROM
        // refuses background activity starts. The other is recents protection: only the
        // overlay route can empty the app's task before the system snapshots it, because an
        // activity on top of the app would swallow the back press that does it. Given the
        // choice between "biometrics without a tap" and "a locked app's contents cannot be
        // read from the overview screen", the user asked for the second.
        if (settings.activityStartBlocked || settings.hideFromRecents) {
            if (overlay(context, pkg)) {
                val why = if (settings.activityStartBlocked) {
                    "activity route already known bad"
                } else {
                    "recents protection needs it"
                }
                LockLog.d("overlay shown ($why)")
                return
            }
            LockLog.w("overlay refused — no 'display over other apps' grant")
            contain(context, pkg)
            return
        }

        val started = withContext(Dispatchers.Main) { LockPresenter.challenge(context, pkg) }
        if (started) {
            delay(LockPresenter.APPEAR_DEADLINE_MS)

            // Two independent signals, and we only give up on the activity when both say
            // nothing appeared: it never reached onResume, *and* the protected app is
            // still the foreground window. If the lock screen had come up, our own
            // package would have been recorded as foreground by the window event it
            // generated.
            if (LockPresenter.wasShown(pkg)) {
                LockLog.d("activity lock screen shown")
                return
            }
            if (LockSession.foregroundPackage != pkg) {
                LockLog.d("foreground moved on before the deadline; leaving it alone")
                return
            }
        }

        // The activity route does not work on this device. Stop using it.
        LockLog.w("background activity start refused by this ROM — switching to overlay")
        AppLockApp.get().settings.setActivityStartBlocked(true)
        LockPresenter.dismiss()
        if (overlay(context, pkg)) {
            LockLog.d("overlay shown")
            return
        }
        LockLog.w("overlay refused — no 'display over other apps' grant")
        contain(context, pkg)
    }

    /**
     * Re-tries the activity because the user asked for biometrics, which the overlay
     * cannot host.
     *
     * Safe to attempt even when `activityStartBlocked` is set: an overlay is a
     * visible window belonging to us, and a visible window is itself a background-start
     * exemption, so this often succeeds where the cold attempt failed. The overlay has to
     * come down first — it would otherwise render *over* the activity — which is why this
     * puts it back if the activity never shows up. Getting it wrong leaves the protected
     * app naked, so the restore is unconditional rather than best-effort.
     */
    fun escalateToActivity(context: Context, pkg: String) {
        AppLockApp.get().appScope.launch {
            withContext(Dispatchers.Main) {
                LockOverlay.hide()
                LockPresenter.challenge(context, pkg, fromOverlay = true)
            }

            delay(LockPresenter.APPEAR_DEADLINE_MS)
            if (LockPresenter.wasShown(pkg)) return@launch

            // No activity, and we took away the only thing that was covering the app.
            withContext(Dispatchers.Main) {
                if (!LockOverlay.show(context, pkg)) contain(context, pkg)
            }
        }
    }

    /**
     * Shows the overlay lock screen and, behind it, closes the protected app's task.
     *
     * The three steps are ordered and none of them can move. The window goes up **without
     * focus** so it covers the app immediately while injected back presses still reach the
     * app rather than our own [LockOverlay.BackAwareRoot]; [TaskShield.arm] then empties the
     * task, which is the only moment at which that is possible — once the user presses home,
     * the system has already captured a snapshot of the app's contents for the overview
     * screen, and nothing after that can take it back. Focus is granted last, and in a
     * `finally`, because a lock screen whose keypad does not respond is worse than an
     * unshielded one.
     */
    private suspend fun overlay(context: Context, pkg: String): Boolean {
        val shown = withContext(Dispatchers.Main) {
            LockOverlay.show(context, pkg, focusable = false)
        }
        if (!shown) return false

        try {
            TaskShield.arm(context, pkg)
        } finally {
            withContext(Dispatchers.Main) { LockOverlay.grantFocus() }
        }
        return true
    }

    /**
     * Whether [pkg] coming to the foreground means the user genuinely navigated away from
     * whatever [LockOverlay] is guarding.
     *
     * Deliberately narrow, because the obvious test — "the foreground package isn't the
     * target" — is wrong in the direction that opens the protected app back up. SystemUI
     * raises a window for every heads-up notification and every pull of the shade, and the
     * keyboard raises one the moment the recovery-answer field takes focus; treating either
     * as an app switch would tear the lock screen down and leave the protected app sitting
     * on screen unlocked. Our own package matters too — adding the overlay can itself be
     * reported as a foreground change, so the window would have dismissed itself the
     * instant it appeared.
     *
     * So only something the user could have launched from their home screen counts. The
     * active input method is excluded on top of that, because a handful of keyboards do
     * ship a launcher activity for their own settings.
     */
    private fun isRealAppSwitch(context: Context, pkg: String): Boolean {
        if (pkg == context.packageName || pkg == "com.android.systemui") return false

        val launchable = runCatching {
            context.packageManager.getLaunchIntentForPackage(pkg) != null
        }.getOrDefault(false)
        if (!launchable) return false

        return pkg != activeInputMethod(context)
    }

    /** Package of the current IME, from the "pkg/.Service" form the system stores. */
    private fun activeInputMethod(context: Context): String? = runCatching {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/')
    }.getOrNull()

    /**
     * Last resort when neither route can present a lock screen: get the protected app off
     * the screen and tell the user why.
     *
     * Bouncing to the launcher is a blunt instrument, but the alternative is showing
     * someone else's photos to whoever picked up the phone. A lock app has to fail
     * closed. [ProtectionAlert] makes sure this never happens mysteriously.
     */
    private fun contain(context: Context, pkg: String) {
        // Do not leave `challenging` latched, or the retry path stays blocked for 8s.
        LockPresenter.dismiss()

        // The one package we refuse to bounce, even having failed to lock it. Settings is
        // the only route to the "display over other apps" grant, and being unable to present
        // a lock screen is overwhelmingly *because* that grant is missing. Pushing home here
        // would mean every attempt to open Settings ends at the launcher, with the fix on the
        // other side of the door. Fail open, loudly, rather than fail closed and unrecoverable.
        if (UninstallGuard.isGuardedSystemPage(pkg)) {
            LockLog.w("could not lock $pkg; leaving it open so the user can fix permissions")
            ProtectionAlert.show(context, pkg, containedByHome = false)
            return
        }

        val pushed = AppLockAccessibilityService.pushHome()
        LockLog.w("could not present a lock screen; pushedHome=$pushed")
        ProtectionAlert.show(context, pkg, containedByHome = pushed)
    }
}
