package com.sabid.applock.core.util

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.sabid.applock.service.AppLockDeviceAdmin

/**
 * Stops AppLock itself from being removed by whoever is holding the phone.
 *
 * A lock app that anyone can uninstall protects nothing: thirty seconds in the app drawer
 * undoes every lock the owner set. Android offers exactly one legitimate lever for this —
 * a package with an active device admin cannot be uninstalled — so that is what AppLock
 * uses, with no policies attached (see `res/xml/device_admin.xml`).
 *
 * Three things are worth being honest about, because a security feature that is oversold is
 * worse than one that is absent:
 *
 *  1. **Deactivation is a Settings page.** Anyone who reaches Settings > Security > Device
 *     admin apps can switch it off and then uninstall normally. Locking Settings
 *     (`guardSystemPages`) is what closes that, and the two are meant to be used together.
 *  2. **Safe mode and adb bypass it.** `adb uninstall`, and on some ROMs booting into safe
 *     mode, do not go through the admin check. Nothing an app can do reaches either.
 *  3. **A factory reset always wins.** That is by design and not something to try to break.
 *
 * There is no stored flag for whether this is on. [DevicePolicyManager.isAdminActive] is
 * the only truth, the user can change it from outside the app at any time, and a cached
 * copy would eventually tell the user they are protected when they are not.
 */
object UninstallGuard {

    /**
     * Packages that can undo AppLock, and so have to be behind the PIN for uninstall
     * protection to mean anything.
     *
     * Not merged into the user's locked-app list on purpose: these are AppLock's own
     * business, the user never chose them, and showing them in the app list would be both
     * confusing and easy to switch off by accident.
     *
     * `com.android.settings` covers three separate attacks with one entry — deactivating
     * the device admin, force-stopping AppLock, and clearing its data. The vendor entries
     * are the same pages under a different package name on ROMs that ship their own.
     */
    private val guarded = setOf(
        "com.android.settings",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.systemui.settings",
        "com.miui.packageinstaller",
        "com.miui.securitycenter",
        "com.samsung.android.packageinstaller",
        "com.samsung.android.lool",
        "com.coloros.safecenter",
        "com.oppo.safe",
        "com.iqoo.secure",
        "com.vivo.permissionmanager",
        "com.huawei.systemmanager",
        "com.oneplus.security",
        "com.transsion.phonemaster"
    )

    /** Whether [pkg] is one of the pages that could be used to remove or disable AppLock. */
    fun isGuardedSystemPage(pkg: String): Boolean = pkg in guarded

    fun isActive(context: Context): Boolean = runCatching {
        policyManager(context)?.isAdminActive(component(context)) == true
    }.getOrDefault(false)

    /**
     * The system's "activate this device admin?" screen.
     *
     * Deliberately returns the intent rather than launching it, so the caller can start it
     * from an activity and see the result. Started from a service context it would need
     * NEW_TASK and would give no signal about whether the user agreed.
     */
    fun activationIntent(context: Context): Intent =
        Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component(context))
            .putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "AppLock needs this so it cannot be uninstalled without your PIN. It " +
                    "claims no other powers: it cannot erase your phone, change your " +
                    "screen lock, or restrict the camera."
            )

    /**
     * Gives up admin, which makes AppLock uninstallable again.
     *
     * Only reachable from inside AppLock's settings, which already sit behind the PIN, so
     * this needs no separate challenge of its own. Returns false if the platform refused.
     */
    fun deactivate(context: Context): Boolean = runCatching {
        policyManager(context)?.removeActiveAdmin(component(context))
        !isActive(context)
    }.getOrDefault(false)

    private fun component(context: Context) =
        ComponentName(context.applicationContext, AppLockDeviceAdmin::class.java)

    private fun policyManager(context: Context) =
        context.getSystemService(DevicePolicyManager::class.java)
}
