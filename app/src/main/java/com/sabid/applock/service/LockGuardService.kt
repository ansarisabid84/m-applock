package com.sabid.applock.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.sabid.applock.AppLockApp
import com.sabid.applock.MainActivity
import com.sabid.applock.R
import com.sabid.applock.core.util.ProtectionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps the process warm so the accessibility service is not reclaimed under memory
 * pressure, and gives the user an honest, always-visible signal that AppLock is running.
 *
 * "Advanced protection" in practice: a foreground service is the strongest legitimate
 * persistence Android offers. START_STICKY plus a MY_PACKAGE_REPLACED / BOOT_COMPLETED
 * receiver covers task-killer and reboot cases without any of the battery-hostile
 * tricks (alarm ping-pong, sticky broadcasts) that get apps flagged on Play.
 */
class LockGuardService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watchdogStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        running = true
        startAsForeground()
        // Start exactly once: onStartCommand is called again on every start(), and a
        // second poll loop would double the battery cost for no extra coverage.
        if (!watchdogStarted) {
            watchdogStarted = true
            UsageWatchdog.start(applicationContext, scope)
            watchProtectionChain()
        }
        return START_STICKY
    }

    /**
     * Watches the whole protection chain and reports it, from the one component most likely
     * to still be alive when something else has died.
     *
     * This service is a foreground service: vendors kill it far less readily than they kill
     * an accessibility service, and START_STICKY plus the boot receiver bring it back when
     * they do. That makes it the right place to notice that detection has gone — and to say
     * so, instead of leaving a notification up that claims "AppLock is protecting your apps"
     * while nothing is being locked at all.
     *
     * Only transitions are acted on, so a broken state that persists for an hour produces one
     * notification rather than two hundred.
     */
    private fun watchProtectionChain() {
        scope.launch {
            var previous: Boolean? = null
            while (isActive) {
                // Never judge the chain against the default settings. This service starts
                // before the first DataStore emission arrives, and the defaults claim no PIN
                // is set — which is how a phone with a perfectly good pattern was told, on
                // every process start, that AppLock was not locking anything.
                if (!AppLockApp.get().settingsLoaded) {
                    delay(SETTINGS_WAIT_MS)
                    continue
                }

                // The locked count is read here rather than inside ProtectionStatus because it
                // is the one fact in the chain that costs a database query, and this loop is
                // already suspending. It decides whether an unset unlock is a problem worth a
                // notification at all: on a fresh install, with nothing on the list, it is not.
                val status = ProtectionStatus.read(
                    context = this@LockGuardService,
                    settings = AppLockApp.get().snapshot,
                    lockedCount = AppLockApp.get().repo.lockedPackages().size
                )
                if (status.working != previous) {
                    previous = status.working
                    LockLog.d("protection chain working=${status.working} (${status.problem?.title})")
                    updateNotification(status)
                    if (status.working) {
                        ProtectionAlert.clearChainBroken(this@LockGuardService)
                    } else {
                        val problem = status.problem
                        if (problem != null) {
                            ProtectionAlert.chainBroken(
                                this@LockGuardService,
                                getString(R.string.notif_guard_title_broken),
                                "${problem.title}. ${problem.detail}"
                            )
                        }
                    }
                }
                delay(CHAIN_CHECK_INTERVAL_MS)
            }
        }
    }

    /** Re-posts the ongoing notification so its wording matches [status]. */
    private fun updateNotification(status: ProtectionStatus) {
        runCatching {
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIF_ID, buildNotification(status.working, status.problem?.title))
        }
    }

    private fun startAsForeground() {
        // Starts optimistic and is corrected by watchProtectionChain's first pass, which runs
        // within milliseconds. Reading the chain here instead would be worse, not better: the
        // accessibility service is often still binding at this point, so the answer would be
        // "stopped" on every single cold start.
        val notification = buildNotification(working = true, problem = null)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    /**
     * The ongoing notification, worded to match reality.
     *
     * It used to read "AppLock is protecting your apps" unconditionally. That is the most
     * frequently seen surface in the whole app, and on a phone that had killed detection it
     * was reassuring the user hour after hour while nothing was being locked.
     */
    private fun buildNotification(working: Boolean, problem: String?): Notification {
        val tap = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, AppLockApp.CHANNEL_GUARD)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(
                if (working) getString(R.string.notif_guard_title)
                else getString(R.string.notif_guard_title_broken)
            )
            .setContentText(
                if (working) getString(R.string.notif_guard_text)
                else problem ?: getString(R.string.notif_guard_text_broken)
            )
            .setContentIntent(tap)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Recents swipe should not disarm protection.
        start(this)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val NOTIF_ID = 1001

        /**
         * How often the protection chain is re-read. Every input is a local read (a
         * Settings.Secure lookup, an AppOps check, two booleans), so this is cheap; the
         * interval is about how quickly the user deserves to be told, not about cost.
         */
        private const val CHAIN_CHECK_INTERVAL_MS = 15_000L

        /** How long to wait between looks for the first settings read to land. */
        private const val SETTINGS_WAIT_MS = 150L

        @Volatile
        private var running = false

        /** Whether the guard (and therefore the usage-stats backstop) is alive. */
        val isRunning: Boolean get() = running

        fun start(context: Context) {
            val intent = Intent(context, LockGuardService::class.java)
            runCatching { context.startForegroundService(intent) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LockGuardService::class.java))
        }
    }
}
