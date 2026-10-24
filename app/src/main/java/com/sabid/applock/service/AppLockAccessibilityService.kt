package com.sabid.applock.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Process
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import com.sabid.applock.AppLockApp
import com.sabid.applock.core.model.RelockPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Watches window changes and puts the lock screen in front of protected apps.
 *
 * Why accessibility and not UsageStatsManager polling: polling costs a wake-up every few
 * hundred milliseconds and still lets the protected UI flash on screen first.
 * Accessibility delivers TYPE_WINDOW_STATE_CHANGED as the window is created, so the lock
 * screen lands before the user can read anything. [UsageWatchdog] is a slow backstop for
 * ROMs that kill this service, not the main path.
 *
 * This service reads only the package name of the foreground window. It never reads
 * window text, never records what you do, and never touches the network.
 */
class AppLockAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_SCREEN_OFF) return
            scope.launch {
                val policy = AppLockApp.get().settings.current().relockPolicy
                if (policy == RelockPolicy.SCREEN_OFF || policy == RelockPolicy.IMMEDIATELY) {
                    LockSession.revokeSession()
                }
                // A locked screen is a natural point to forgive the back-off, but only
                // if it has already elapsed — otherwise locking the screen would be a
                // free way to skip the wait.
                if (!LockSession.isLockedOut) LockSession.clearFailures()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = this
        LockLog.d("detection service connected")
        // Whatever we complained about while unbound is now untrue. Leaving the alert up
        // after recovery is how a lock app teaches its user to ignore its warnings.
        ProtectionAlert.clear(this)
        LockEngine.exemptions(this)
        registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        LockGuardService.start(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        lastWindowEventAt = SystemClock.elapsedRealtime()
        scope.launch { LockEngine.onForeground(this@AppLockAccessibilityService, pkg) }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        connected = null
        // If this appears without you having turned AppLock off in Accessibility, the ROM
        // killed detection — the single most common reason nothing locks on MIUI.
        LockLog.w("detection service destroyed")
        runCatching { unregisterReceiver(screenReceiver) }
        // The service is going away, so the in-process grant map is about to become
        // unreachable anyway. Drop it explicitly so a restart cannot inherit grants.
        LockSession.revokeAll()
        LockSession.foregroundPackage = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        /**
         * The live service instance, or null when the system has not bound us.
         *
         * This doubles as the only trustworthy answer to "is detection actually running?".
         * Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES says the user ticked the box; it
         * does not say the ROM has kept the service alive, and on Xiaomi/Oppo/Vivo those
         * are routinely different facts.
         */
        @Volatile
        private var connected: AppLockAccessibilityService? = null

        val isRunning: Boolean get() = connected != null

        /** When the last window change of any kind arrived. See [quietForMs]. */
        @Volatile
        private var lastWindowEventAt = 0L

        /**
         * How long the window stream has been still, in milliseconds.
         *
         * [TaskShield] uses this as its "has the app finished launching?" signal, and it is
         * the only one available: this service deliberately does not request
         * `canRetrieveWindowContent`, so there is no way to ask what the top window actually
         * is. A cold app start produces a burst of window changes — starting window, then the
         * real activity, sometimes a permission or ad interstitial — and pressing back in the
         * middle of that burst is what left first launches half-started. Quiet means the burst
         * is over.
         *
         * [Long.MAX_VALUE] until the first event, which reads as "nothing is happening" and is
         * the right answer for a service that has only just bound.
         */
        fun quietForMs(): Long =
            if (lastWindowEventAt == 0L) Long.MAX_VALUE
            else SystemClock.elapsedRealtime() - lastWindowEventAt

        /**
         * How long after process start we refuse to conclude that detection is dead.
         *
         * The system binds accessibility services asynchronously, and the process is
         * routinely started by something else first — the launcher icon, START_STICKY
         * restarting [LockGuardService], BOOT_COMPLETED. In that window [isRunning] is
         * legitimately false, and reading it then is why AppLock announced "your phone
         * killed the service" on ordinary cold starts, over and over, when nothing was
         * wrong. A few seconds is far longer than a bind takes and far shorter than a user
         * would tolerate being lied to.
         */
        private const val BIND_GRACE_MS = 8_000L

        /**
         * Whether [isRunning] is old enough to be trusted as a negative.
         *
         * True as soon as we are bound; otherwise only once the grace window has passed.
         * Uses the real process start time rather than a field initialised on first touch,
         * because when this class is first touched is itself part of what varies.
         */
        val bindSettled: Boolean
            get() = connected != null ||
                SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime() > BIND_GRACE_MS

        /**
         * Sends the user to their launcher. Used only as containment when the lock screen
         * could not be presented — see [LockEngine.contain].
         *
         * Returns false if we have no live service, in which case the caller has no way
         * to get the protected app off the screen and should say so rather than pretend.
         */
        fun pushHome(): Boolean {
            val service = connected ?: return false
            return runCatching { service.performGlobalAction(GLOBAL_ACTION_HOME) }
                .getOrDefault(false)
        }

        /**
         * Presses back on the user's behalf, to finish the activity that is on top.
         *
         * Used by [TaskShield] to empty a declined app's task so it stops appearing in
         * recents. Only safe once our own lock window is gone: the overlay takes focus, so
         * an injected back would land on it and be read as another "give up".
         */
        fun pressBack(): Boolean {
            val service = connected ?: return false
            return runCatching { service.performGlobalAction(GLOBAL_ACTION_BACK) }
                .getOrDefault(false)
        }
    }
}
