package com.sabid.applock.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sabid.applock.AppLockApp
import kotlinx.coroutines.launch

/**
 * Keeps the protected-app list in step with what is actually installed.
 *
 * On install, honours the `lockNewApps` setting — the whole point of which is that a
 * newly-installed app is protected before its first launch, so there is no window where
 * a child installs a browser and uses it freely until the parent notices.
 *
 * On uninstall, drops the row. Leaving it behind meant reinstalling an app silently
 * re-locked it, and the settings screen counted apps that no longer existed.
 */
class PackageInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pkg = intent.data?.schemeSpecificPart ?: return
        if (pkg == context.packageName) return

        // Before anything else, and regardless of what the action turns out to be. The engine
        // caches the set of packages it must never challenge, and that set includes every app
        // answering CATEGORY_HOME. Installing a new launcher changes the answer, and a stale
        // cache there means challenging the home screen — which is unrecoverable without a
        // reboot, since dismissing the lock screen returns to home and raises it again.
        LockEngine.invalidateExemptions()

        // An upgrade arrives as REMOVED(replacing) + ADDED(replacing); ignore both so a
        // Play Store update cannot flip an app the user deliberately left unlocked.
        if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return

        val pending = goAsync()
        val app = AppLockApp.get()
        app.appScope.launch {
            try {
                when (intent.action) {
                    Intent.ACTION_PACKAGE_ADDED ->
                        if (app.settings.current().lockNewApps) app.repo.lock(pkg)

                    Intent.ACTION_PACKAGE_FULLY_REMOVED,
                    Intent.ACTION_PACKAGE_REMOVED -> {
                        app.repo.unlock(pkg)
                        LockSession.revoke(pkg)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
