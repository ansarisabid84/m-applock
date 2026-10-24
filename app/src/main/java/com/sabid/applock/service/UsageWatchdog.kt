package com.sabid.applock.service

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.sabid.applock.AppLockApp
import com.sabid.applock.core.util.Permissions
import com.sabid.applock.core.util.SpecialGrant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Polling backstop for foreground-app detection.
 *
 * Several popular ROMs (Xiaomi, Oppo, Vivo and friends) kill accessibility services
 * aggressively in the background, which silently disarms an app locker — the user only
 * finds out when a protected app opens unchallenged. This watchdog reads
 * UsageStatsManager instead, which nothing can revoke without the user noticing, and
 * feeds [LockEngine] exactly as the accessibility service does.
 *
 * It changes gear depending on whether it is the safety net or the only net. While
 * accessibility is alive this ticks over slowly and does nothing but notice if that stops
 * being true; the moment accessibility is gone it becomes the primary detector and polls
 * fast enough to get a lock screen up before the protected app can be read. Nothing
 * double-fires when both are running, because both go through [LockEngine], which no-ops
 * when the foreground package has not changed.
 *
 * Also the reason AppLock asks for PACKAGE_USAGE_STATS at all. The permission was in the
 * manifest and requested during onboarding while nothing read it, which is exactly the
 * kind of unexplained permission that makes a security app untrustworthy.
 */
object UsageWatchdog {

    /**
     * Poll interval while the accessibility service is bound. Slow on purpose: accessibility
     * is already delivering window changes as they happen, so this is only here to catch the
     * moment accessibility dies.
     */
    private const val BACKSTOP_INTERVAL_MS = 1_500L

    /**
     * Poll interval while nothing else is watching.
     *
     * This is the number that decides whether a phone that kills accessibility services
     * still has a working app lock, so it is aggressive on purpose — a third of a second is
     * about as long as a protected app can be on screen before the user has read something.
     * It is only paid while the screen is on, protection is enabled, and the fast detector
     * is actually absent.
     */
    private const val PRIMARY_INTERVAL_MS = 350L

    private const val POWER_SAVER_INTERVAL_MS = 4_000L

    /**
     * How far back each poll looks for a foreground event.
     *
     * Comfortably wider than the interval, so windows overlap and a event delivered a little
     * late is still seen. Re-reading an event we already handled costs nothing:
     * [LockEngine.onForeground] no-ops unless the package changed.
     */
    private fun lookbackFor(interval: Long): Long = maxOf(interval * 4, 2_000L)

    /**
     * MOVE_TO_FOREGROUND was renamed ACTIVITY_RESUMED in API 29. Both constants are 1, so
     * this is purely about not compiling against a deprecated name on new SDKs.
     */
    private val foregroundEventType: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            UsageEvents.Event.ACTIVITY_RESUMED
        } else {
            @Suppress("DEPRECATION") UsageEvents.Event.MOVE_TO_FOREGROUND
        }

    /**
     * Most recent package to come to the foreground, or null when unknown.
     *
     * queryEvents is used rather than queryUsageStats because the latter buckets by day
     * and its "last time used" is far too coarse to tell which app is on screen now.
     */
    fun currentForeground(context: Context, lookbackMs: Long = 10_000L): String? {
        val manager = context.getSystemService(UsageStatsManager::class.java) ?: return null
        val now = System.currentTimeMillis()
        return runCatching {
            val events = manager.queryEvents(now - lookbackMs, now)
            val event = UsageEvents.Event()
            var latest: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == foregroundEventType) latest = event.packageName
            }
            latest
        }.getOrNull()
    }

    /**
     * Starts the poll loop on [scope]. Cancelling the scope stops it.
     *
     * The interval adapts on every pass rather than being fixed at start, because the thing
     * it depends on — whether the accessibility service is alive — is exactly the thing that
     * changes without warning on the ROMs this exists for. When the service is killed
     * mid-session, this speeds up to primary-detector rates within one tick; when the system
     * rebinds it, this drops back to a cheap backstop.
     */
    fun start(context: Context, scope: CoroutineScope) {
        scope.launch {
            val power = context.getSystemService(PowerManager::class.java)
            while (isActive) {
                val settings = AppLockApp.get().snapshot

                // Skip the whole read when it cannot possibly matter. Waking up to query
                // usage stats with the screen off is pure battery drain.
                val worthReading = settings.protectionEnabled &&
                    power?.isInteractive != false &&
                    Permissions.has(context, SpecialGrant.USAGE_ACCESS)

                val interval = when {
                    // Nothing to detect with, or nothing to detect. Don't spend the fast
                    // interval re-establishing that several times a second.
                    !worthReading -> BACKSTOP_INTERVAL_MS
                    settings.powerSaver -> POWER_SAVER_INTERVAL_MS
                    !AppLockAccessibilityService.isRunning -> PRIMARY_INTERVAL_MS
                    else -> BACKSTOP_INTERVAL_MS
                }

                if (worthReading) {
                    val pkg = currentForeground(context, lookbackFor(interval))
                    if (pkg != null) runCatching { LockEngine.onForeground(context, pkg) }
                }

                // Delay last, so a service that has just been restarted onto a protected app
                // already on screen challenges it immediately instead of a tick later.
                delay(interval)
            }
        }
    }
}
