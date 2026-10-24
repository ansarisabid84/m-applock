package com.sabid.applock

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.sabid.applock.core.data.AppDatabase
import com.sabid.applock.core.data.AppSettings
import com.sabid.applock.core.data.LockRepository
import com.sabid.applock.core.data.SettingsStore
import com.sabid.applock.core.util.IntruderPhotos
import com.sabid.applock.core.util.LauncherIcon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppLockApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var settings: SettingsStore; private set
    lateinit var repo: LockRepository; private set

    /**
     * Last known settings, kept current by a process-lifetime collector.
     *
     * Broadcast receivers and the Quick Settings tile need protectionEnabled on the main
     * thread with no chance to suspend. They used runBlocking on the DataStore flow,
     * which reads from disk on first access and ANRs the receiver; reading this instead
     * is free. Values may be one emission stale, which is fine for a display toggle.
     */
    @Volatile
    var snapshot: AppSettings = AppSettings(); private set

    /**
     * Whether [snapshot] has been read from disk yet, rather than still being the defaults.
     *
     * Anything that *judges* the settings has to wait for this, as opposed to the tile and the
     * widget, which merely display them and can afford to be one emission behind. The defaults
     * say no secret is set, so the protection-chain watcher — which starts before the first
     * emission arrives — announced "No PIN or pattern is set" on process start to users who
     * had one. A security app that cries wolf on every restart teaches people to dismiss the
     * one warning that matters.
     */
    @Volatile
    var settingsLoaded = false; private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = SettingsStore(this)
        repo = LockRepository(this, AppDatabase.get(this), settings)
        createChannels()
        // Runs before anything else can need it: an install upgrading from a build that had
        // the icon-disguise feature may have this app's only launcher alias explicitly
        // disabled, which would make AppLock unreachable from the home screen.
        LauncherIcon.ensureVisible(this)
        appScope.launch { settings.flow.collect { snapshot = it; settingsLoaded = true } }

        // Retention is enforced here rather than on a scheduled job on purpose: this runs on
        // every process start, including the one the accessibility service triggers at boot,
        // so a phone that is simply used keeps sweeping. A WorkManager job would be one more
        // thing for an aggressive ROM to kill — and these ROMs demonstrably do kill our
        // components — leaving photos sitting past the window the user was promised.
        appScope.launch { runCatching { repo.pruneEvents(90L * 24 * 60 * 60 * 1000) } }

        appScope.launch {
            runCatching {
                // Order matters: convert the encrypted captures from earlier builds first so
                // the sweep and the thumbnail warm-up both see one kind of file, then throw
                // away what is past its retention window before spending anything rendering
                // it, then make sure every survivor has a thumbnail so the first open of the
                // gallery is as fast as the second.
                IntruderPhotos.migrateLegacy(this@AppLockApp)
                IntruderPhotos.sweep(this@AppLockApp)
                IntruderPhotos.warmThumbnails(this@AppLockApp)
            }
        }
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_GUARD, getString(R.string.channel_guard), NotificationManager.IMPORTANCE_MIN)
                .apply { setShowBadge(false); lockscreenVisibility = android.app.Notification.VISIBILITY_SECRET }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_INTRUDER, getString(R.string.channel_intruder), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { lockscreenVisibility = android.app.Notification.VISIBILITY_SECRET }
        )
        // High importance on purpose. This channel carries exactly one message — "AppLock
        // just failed to lock something" — and the whole point is that it cannot be missed.
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERT, getString(R.string.channel_alert), NotificationManager.IMPORTANCE_HIGH)
        )
    }

    companion object {
        const val CHANNEL_GUARD = "guard"
        const val CHANNEL_INTRUDER = "intruder"
        const val CHANNEL_ALERT = "alert"

        @Volatile private var instance: AppLockApp? = null
        fun get(): AppLockApp = requireNotNull(instance) { "AppLockApp not created yet" }
    }
}
