package com.sabid.applock.core.util

import android.content.Context
import com.sabid.applock.core.data.AppSettings
import com.sabid.applock.service.AppLockAccessibilityService
import com.sabid.applock.service.LockGuardService

/**
 * An honest, end-to-end answer to "will opening a protected app actually ask for my PIN?".
 *
 * Every individual fact here was already available somewhere, and that was the problem:
 * the app could be in a state where a locked app opens straight through, while the Apps
 * screen said "1 locked", Settings said accessibility was "Active", and nothing anywhere
 * mentioned that the chain was broken. This collapses the whole chain into one verdict so
 * a single surface can tell the truth.
 *
 * The chain, in the order it has to hold:
 *  1. protection is switched on
 *  2. there is something to enforce — at least one locked app, or the tamper guard
 *  3. an unlock exists and can be answered
 *  4. we may display over other apps, or the lock screen cannot be put in front of anything
 *  5. *some* detector is alive — the accessibility service, or the usage-stats poller
 *
 * Point 5 is deliberately an either/or, and that is a correction rather than a relaxation.
 * This used to require the accessibility service specifically, so a ROM that killed it made
 * AppLock declare that nothing was being locked — while the [LockGuardService] poller went
 * on locking apps perfectly well, about a second slower. Announcing total failure during a
 * partial one is its own kind of lie, and it trained the user to distrust the banner that
 * matters.
 *
 * Point 2 is the same principle applied to a fresh install. First run no longer forces a PIN,
 * so the very first thing a new user sees would otherwise be a red banner telling them nothing
 * is being locked — which is true, and useless, because they have not asked for anything to be
 * locked yet. A warning nobody can act on is how you teach someone to ignore warnings.
 */
data class ProtectionStatus(
    val protectionEnabled: Boolean,

    /** An unlock is configured *and* can still be answered. See [Unlock.usable]. */
    val unlockUsable: Boolean,

    /**
     * Whether the user has picked an unlock at all, as opposed to having picked one that has
     * since stopped working. The two produce different advice, so they are kept apart.
     */
    val unlockChosen: Boolean,

    /**
     * Whether the phone itself has a screen lock, regardless of whether AppLock uses it.
     *
     * Carried here rather than read where it is needed so the UI gets it on the same
     * once-a-second refresh as everything else — it is the one fact in this whole chain that
     * the user can change without AppLock being on screen at all.
     */
    val phoneScreenLockSet: Boolean,

    /** How many apps are on the locked list right now. */
    val lockedCount: Int,

    /** Whether the tamper guard is on, which is a thing to enforce even with nothing locked. */
    val tamperGuardEnabled: Boolean,
    val accessibilityGranted: Boolean,
    val accessibilityRunning: Boolean,
    val accessibilitySettled: Boolean,
    val overlayGranted: Boolean,
    val usageAccess: Boolean,
    val guardRunning: Boolean,
    val autostartPageAvailable: Boolean
) {
    /**
     * Detection by polling UsageStatsManager instead of by accessibility events.
     *
     * Slower — up to a poll interval of the protected app being on screen — but it lives in
     * a foreground service, which ROMs kill far less readily than an accessibility service,
     * and nothing can revoke usage access without the user doing it themselves.
     */
    val fallbackDetection: Boolean get() = guardRunning && usageAccess

    val detectionWorking: Boolean get() = accessibilityRunning || fallbackDetection

    /**
     * Whether AppLock has been asked to guard anything at all.
     *
     * False on a fresh install, and that is the point: with nothing on the locked list and the
     * tamper guard off, there is no protection to be broken, so none of the warnings below are
     * worth showing yet.
     */
    val enforcing: Boolean get() = lockedCount > 0 || tamperGuardEnabled

    /** Whether a protected app opening right now would be challenged. */
    val working: Boolean
        get() = protectionEnabled && overlayGranted && detectionWorking &&
            (unlockUsable || !enforcing)

    /** True for problems that mean nothing is being locked at all, not just degraded. */
    val broken: Boolean get() = !working

    /** What to tell the user, and where to send them. Null when nothing is wrong. */
    val problem: Problem?
        get() = when {
            !protectionEnabled -> Problem(
                "Protection is switched off",
                "Nothing will lock until you turn it back on in Settings.",
                null
            )

            // Only once there is something to enforce — see [enforcing]. Both of these carry
            // setUpUnlock, so the banner can offer the fix in place instead of describing it.
            !unlockUsable && enforcing && unlockChosen -> Problem(
                "Your phone's screen lock is gone",
                "AppLock was set to use it, and it has been removed — so there is nothing " +
                    "left to check. ${lockedApps()} opening normally until you set an unlock " +
                    "again.",
                null,
                setUpUnlock = true
            )

            !unlockUsable && enforcing -> Problem(
                "No unlock is set yet",
                "${lockedApps()} on your list, but AppLock has nothing to check — so they " +
                    "open normally. Set a PIN, a pattern, or use your phone's own screen lock.",
                null,
                setUpUnlock = true
            )

            // Reported before detection, because it is the more fundamental of the two: a
            // detector with nowhere to draw can only bounce you to the home screen.
            !overlayGranted -> Problem(
                "Cannot display over other apps",
                "This is the permission the lock screen itself is drawn with. Without it " +
                    "AppLock can only bounce you back to your home screen when you open a " +
                    "protected app — it cannot ask for your PIN.",
                SpecialGrant.OVERLAY
            )

            !accessibilityGranted && !fallbackDetection -> Problem(
                "App detection is off",
                "AppLock cannot see which app you opened, so it can never lock one. " +
                    "Turn on AppLock under Settings > Accessibility.",
                SpecialGrant.ACCESSIBILITY
            )

            !accessibilityGranted -> Problem(
                "Running on backup detection",
                "Apps still lock, but up to a second later because AppLock is polling " +
                    "instead of being told. Turning on AppLock under Accessibility makes " +
                    "the lock screen appear instantly.",
                SpecialGrant.ACCESSIBILITY
            )

            // Granted but not bound. Only trusted once the bind window has passed —
            // see AppLockAccessibilityService.bindSettled.
            !accessibilityRunning && accessibilitySettled -> stoppedByRom()

            !guardRunning -> Problem(
                "Background guard is not running",
                "Locking still works while AppLock is warm, but it may stop after your " +
                    "phone clears memory. Exempting AppLock from battery optimisation fixes it.",
                SpecialGrant.BATTERY
            )

            !usageAccess -> Problem(
                "No backup detection",
                "Locking works. Granting usage access lets AppLock keep working on phones " +
                    "that kill the accessibility service.",
                SpecialGrant.USAGE_ACCESS
            )

            else -> null
        }

    /**
     * The vendor-kill case, which is common enough to deserve its own wording.
     *
     * Two versions on purpose. Re-ticking the Accessibility switch is what fixes it *now*;
     * the vendor autostart allow-list is what stops it happening again, and sending the user
     * back to Accessibility for the fourth time without mentioning that is why this keeps
     * being reported as unfixable. Where a vendor page exists, that is where "tap to fix"
     * goes, and the text says to do the toggle too.
     */
    private fun stoppedByRom(): Problem {
        val vendor = OemSettings.vendorName
        val covered = if (fallbackDetection) {
            "Backup detection is covering it, so protected apps still lock — up to a " +
                "second later than usual. "
        } else {
            "Nothing is being locked right now. "
        }
        return if (autostartPageAvailable) {
            Problem(
                if (fallbackDetection) "$vendor stopped fast detection" else "App detection was stopped",
                covered + "$vendor kills background services unless the app is on its " +
                    "autostart list — Android's own battery setting does not cover that " +
                    "list. Tap to open it and allow AppLock, then switch AppLock off and " +
                    "on again under Settings > Accessibility.",
                SpecialGrant.AUTOSTART
            )
        } else {
            Problem(
                if (fallbackDetection) "Your phone stopped fast detection" else "App detection was stopped",
                covered + "The permission is still granted — your phone killed the " +
                    "service. Switch AppLock off and on again under Accessibility, and " +
                    "exempt it from battery optimisation so it stops happening.",
                SpecialGrant.ACCESSIBILITY
            )
        }
    }

    /** "1 app is" / "4 apps are", so the sentences above read as sentences. */
    private fun lockedApps(): String =
        if (lockedCount == 1) "1 app is" else "$lockedCount apps are"

    /**
     * What to tell the user and how they can act on it.
     *
     * [fix] is a system Settings page AppLock can send them to. [setUpUnlock] is the one fix
     * that lives inside AppLock instead, so it cannot be expressed as a [SpecialGrant] — the
     * banner turns it into a button rather than making the whole card tappable, because
     * "set up an unlock" is a real choice with branches and not a jump to a toggle.
     */
    data class Problem(
        val title: String,
        val detail: String,
        val fix: SpecialGrant?,
        val setUpUnlock: Boolean = false
    )

    companion object {
        /**
         * [lockedCount] has to be passed in rather than read here: this is called from a
         * composable that polls every second and from the guard service's own loop, and neither
         * wants a database round-trip hidden inside what is otherwise a handful of local reads.
         * Both callers already hold the number.
         */
        fun read(context: Context, settings: AppSettings, lockedCount: Int): ProtectionStatus {
            val granted = Permissions.hasAccessibility(context)
            return ProtectionStatus(
                protectionEnabled = settings.protectionEnabled,
                unlockUsable = Unlock.usable(context, settings),
                unlockChosen = Unlock.chosen(settings),
                phoneScreenLockSet = Unlock.deviceCredentialSet(context),
                lockedCount = lockedCount,
                tamperGuardEnabled = settings.guardSystemPages,
                accessibilityGranted = granted,
                // Enabled in Settings is a different fact from bound and alive. Require both:
                // a ROM that killed the service leaves the first true and the second false.
                accessibilityRunning = granted && AppLockAccessibilityService.isRunning,
                accessibilitySettled = AppLockAccessibilityService.bindSettled,
                overlayGranted = Permissions.hasOverlay(context),
                usageAccess = Permissions.hasUsageAccess(context),
                guardRunning = LockGuardService.isRunning,
                autostartPageAvailable = OemSettings.isAvailable(context)
            )
        }
    }
}
