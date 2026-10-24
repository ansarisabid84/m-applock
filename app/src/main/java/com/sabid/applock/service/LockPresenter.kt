package com.sabid.applock.service

import android.content.Context
import android.content.Intent
import com.sabid.applock.ui.lock.LockActivity

/**
 * The activity route to the lock screen: the preferred one, but not the reliable one.
 *
 * An activity is worth preferring because BiometricPrompt requires a FragmentActivity and
 * because a normal window has a lifetime the system manages for us. What it is not is
 * dependable. Starting an activity from a background service is blocked on Android 10+
 * unless the process holds an exemption, and holding SYSTEM_ALERT_WINDOW turns out not to
 * be enough on plenty of real devices.
 *
 * The part that made this class actively untrustworthy: when the system refuses a
 * background activity start it does **not** throw. startActivity() returns normally, logs
 * a line nobody sees, and no window appears. So `runCatching` reported success,
 * `challenging` latched to the package, and the protected app sat open with no lock screen
 * and no error anywhere — the exact "I locked it and it just opened" failure.
 *
 * Hence the handshake: [LockActivity] calls [confirmShown] once it is actually on screen,
 * and the caller checks [wasShown] after [APPEAR_DEADLINE_MS]. Silence is detectable, so
 * [LockEngine] can fall through to [LockOverlay], which cannot be suppressed at all.
 */
object LockPresenter {

    /**
     * If a challenge was requested this long ago and we are being asked again, assume
     * the activity never actually came up (start suppressed, crash, OEM kill) and retry.
     * Without this, a single failed start would latch `challenging` and leave the
     * package permanently unprotected for the life of the process.
     */
    private const val STALE_AFTER_MS = 8_000L

    /**
     * How long to give the activity to reach onResume before we treat the start as
     * blocked. Generous: a cold-ish activity start on a budget phone under load can
     * take most of a second, and a false alarm here bounces the user to their launcher.
     */
    const val APPEAR_DEADLINE_MS = 1_500L

    @Volatile
    private var requestedAt = 0L

    @Volatile
    private var shownFor: String? = null

    /**
     * Requests the lock screen for [packageName].
     *
     * [fromOverlay] marks a start made while an overlay lock screen was on screen. Those
     * succeed under a different exemption (we had a visible window) and so say nothing
     * about whether cold background starts work — [LockActivity] needs to know the
     * difference before it decides it can trust this route again.
     *
     * Returns false only when the start threw outright. A true return means the start
     * was *accepted*, not that anything appeared — call [wasShown] after
     * [APPEAR_DEADLINE_MS] to learn that.
     */
    fun challenge(context: Context, packageName: String, fromOverlay: Boolean = false): Boolean {
        val inFlight = LockSession.challenging == packageName &&
            System.currentTimeMillis() - requestedAt < STALE_AFTER_MS
        if (inFlight) return true

        LockSession.challenging = packageName
        requestedAt = System.currentTimeMillis()
        shownFor = null

        val intent = Intent(context, LockActivity::class.java)
            .putExtra(LockActivity.EXTRA_TARGET_PACKAGE, packageName)
            .putExtra(LockActivity.EXTRA_FROM_OVERLAY, fromOverlay)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION
            )
        return runCatching { context.startActivity(intent) }
            .onFailure {
                LockSession.challenging = null
                requestedAt = 0L
            }
            .isSuccess
    }

    /** Called by [LockActivity] from onResume: proof that the lock screen is on screen. */
    fun confirmShown(packageName: String) {
        shownFor = packageName
    }

    /** True once the lock screen for [packageName] has actually been seen on screen. */
    fun wasShown(packageName: String): Boolean = shownFor == packageName

    fun dismiss() {
        LockSession.challenging = null
        requestedAt = 0L
        // shownFor is deliberately left alone. It answers "did a lock screen for this
        // package ever reach the screen", and dismiss() is called from onPause — so
        // clearing it here made a lock screen that appeared and was then navigated away
        // from read as one that never appeared, which is the cue to bounce the user to
        // their launcher. Every challenge() resets it anyway.
    }
}
