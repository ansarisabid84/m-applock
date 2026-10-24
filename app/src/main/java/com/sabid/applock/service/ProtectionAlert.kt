package com.sabid.applock.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.sabid.applock.AppLockApp
import com.sabid.applock.MainActivity
import com.sabid.applock.R
import com.sabid.applock.core.util.OemSettings

/**
 * Tells the user, loudly, when AppLock could not lock something it was asked to lock.
 *
 * This exists because the failure it reports used to be completely invisible. The
 * accessibility service saw the protected app, decided to challenge it, asked the system
 * for a lock screen, was refused, and reported success — so the app opened normally and
 * every surface in AppLock still said "protected". A lock that quietly stops working is
 * worse than no lock, because the user keeps trusting it.
 *
 * Deliberately on its own high-importance channel. The guard channel is IMPORTANCE_MIN
 * and VISIBILITY_SECRET by design, so posting this there would hide the one message the
 * user actually needs to see.
 */
object ProtectionAlert {

    private const val NOTIF_ID = 1003
    private const val CHAIN_NOTIF_ID = 1004
    private const val RECENTS_NOTIF_ID = 1005

    /** Don't re-alert more than this often; the trigger can repeat on every app switch. */
    private const val COOLDOWN_MS = 60_000L

    /**
     * Chain-broken alerts are raised on a state *transition*, so they cannot repeat while the
     * state persists. This only guards against a process that restarts over and over while
     * broken, where each start would otherwise re-announce the same thing.
     */
    private const val CHAIN_COOLDOWN_MS = 30 * 60_000L

    /**
     * Once a day is plenty for [recentsUncovered]. It is advice about a vendor setting, not news,
     * and the thing it is advising about stops being retried within the same process anyway.
     */
    private const val RECENTS_COOLDOWN_MS = 24 * 60 * 60_000L

    @Volatile
    private var lastShownAt = 0L

    @Volatile
    private var lastChainAlertAt = 0L

    @Volatile
    private var lastRecentsAlertAt = 0L

    fun show(context: Context, packageName: String, containedByHome: Boolean) {
        val now = System.currentTimeMillis()
        if (now - lastShownAt < COOLDOWN_MS) return
        lastShownAt = now

        val label = labelOf(context, packageName)
        val body = if (containedByHome) {
            "$label was closed because the lock screen could not open. Tap to fix the " +
                "permission that is blocking it."
        } else {
            "$label opened without asking for your PIN. AppLock could not show the lock " +
                "screen. Tap to fix it."
        }

        val tap = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, AppLockApp.CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("AppLock could not lock an app")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        // Posting is best-effort: on Android 13+ the user may have denied notifications,
        // and that must not take down the accessibility service.
        runCatching {
            context.getSystemService(NotificationManager::class.java)
                ?.notify(NOTIF_ID, notification)
        }
    }

    fun clear(context: Context) {
        lastShownAt = 0L
        runCatching {
            context.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID)
        }
    }

    /**
     * Says that protection has stopped working *before* a protected app slips through.
     *
     * [show] is retrospective — it fires after something already opened unchallenged, which
     * is one app too late. This is the same message delivered on the way in: when a vendor
     * kills the detection service the user has no way to find out except by opening AppLock
     * and reading the banner, which is precisely why the failure kept being discovered days
     * after the fact. Raised from [LockGuardService], which survives the kill that takes the
     * accessibility service down.
     */
    fun chainBroken(context: Context, title: String, detail: String) {
        val now = System.currentTimeMillis()
        if (now - lastChainAlertAt < CHAIN_COOLDOWN_MS) return
        lastChainAlertAt = now

        val tap = PendingIntent.getActivity(
            context, 1,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, AppLockApp.CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setOngoing(false)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        runCatching {
            context.getSystemService(NotificationManager::class.java)
                ?.notify(CHAIN_NOTIF_ID, notification)
        }
    }

    /**
     * Says that the app switcher can still show a locked app the user has been inside.
     *
     * Only raised once this has been tried and refused several times over. Closing a locked app
     * behind the user needs it brought back in front for a moment, that needs a background
     * activity start, and a handful of ROMs — Xiaomi's among them — refuse those outright until
     * the user allows "display pop-up windows while running in the background" by hand. There is
     * no API to ask for that permission and none to read it, so the only thing AppLock can do is
     * say so and point at the page. Tapping lands in AppLock, where the vendor-settings shortcut
     * lives. See [com.sabid.applock.core.util.OemSettings].
     */
    fun recentsUncovered(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastRecentsAlertAt < RECENTS_COOLDOWN_MS) return
        lastRecentsAlertAt = now

        val body = "${OemSettings.vendorName} will not let AppLock close a locked app after " +
            "you leave it, so the app switcher can still show what you were looking at. Tap to " +
            "allow AppLock to start apps in the background, or turn this off in Settings."

        val tap = PendingIntent.getActivity(
            context, 2,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, AppLockApp.CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("Locked apps can still be seen in the app switcher")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        runCatching {
            context.getSystemService(NotificationManager::class.java)
                ?.notify(RECENTS_NOTIF_ID, notification)
        }
    }

    /** Takes down [chainBroken] once protection is working again. */
    fun clearChainBroken(context: Context) {
        lastChainAlertAt = 0L
        runCatching {
            context.getSystemService(NotificationManager::class.java)?.cancel(CHAIN_NOTIF_ID)
        }
    }

    private fun labelOf(context: Context, packageName: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)
}
