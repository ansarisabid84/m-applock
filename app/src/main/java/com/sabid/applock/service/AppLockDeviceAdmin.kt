package com.sabid.applock.service

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/**
 * Registered so that AppLock cannot be uninstalled while it is switched on.
 *
 * Android will not let a package be removed while one of its device admins is active —
 * the uninstall dialog refuses outright and points the user at Settings. That single
 * property is the whole reason this class exists; see [com.sabid.applock.core.util.UninstallGuard]
 * and the empty policy set in `res/xml/device_admin.xml`.
 *
 * The obvious hole is that deactivating the admin is itself just a Settings page, so the
 * protection is only as strong as the lock on Settings. That is what
 * `guardSystemPages` is for, and it is why [onDisableRequested] warns rather than assumes.
 */
class AppLockDeviceAdmin : DeviceAdminReceiver() {

    /**
     * Shown on the confirmation screen when someone tries to deactivate the admin.
     *
     * Returning a non-null string forces Android to put up a second confirmation with this
     * text on it, which turns a one-tap mistake into a deliberate act.
     */
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        "Turning this off will let AppLock be uninstalled, which removes the lock from " +
            "every app you have protected."

    override fun onEnabled(context: Context, intent: Intent) {
        LockLog.d("uninstall protection enabled")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        // Nothing is persisted about admin state — DevicePolicyManager is the single source
        // of truth and every surface reads it live, so there is no flag here to get out of
        // step. Worth a log line all the same: this is the moment AppLock became
        // uninstallable, and it is the first thing to look for when one goes missing.
        LockLog.w("uninstall protection disabled — AppLock can now be removed")
    }
}
