package com.sabid.applock.core.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/**
 * Deep links into the vendor-specific "let this app keep running" pages.
 *
 * Android's own battery-optimisation exemption is not the switch that matters on the
 * phones people actually complain about. Xiaomi, Oppo, Vivo, Huawei and their siblings
 * each ship a second, parallel restriction system of their own — Autostart, Background
 * pop-ups, "protected apps", per-app battery saver — that is *not* covered by
 * REQUEST_IGNORE_BATTERY_OPTIMIZATIONS and has no public API. Leave those off and the ROM
 * will keep killing the accessibility service no matter how many times the user re-ticks
 * it under Accessibility, which is exactly the "it disables itself again and again"
 * complaint that brought us here.
 *
 * There is no API to read these, and none to set them. All an app can do is take the user
 * to the right page, which is what this is for.
 *
 * Everything is resolved rather than assumed. These components are undocumented, they get
 * renamed between ROM versions, and a hard-coded `startActivity` at one of them on a phone
 * that has moved it is an ActivityNotFoundException. Only components that actually exist on
 * *this* device are ever offered, so a stock or Pixel device simply reports none and the UI
 * never mentions the subject.
 */
object OemSettings {

    /**
     * Autostart / background-launch allow-lists, most-specific first.
     *
     * Several vendors are listed twice because the component moved between ROM versions and
     * both spellings are still in the field.
     */
    private val autostart = listOf(
        // Xiaomi / Redmi / POCO — MIUI and HyperOS
        "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
        // Huawei / Honor
        "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
        // Oppo / realme — ColorOS
        "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
        "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
        "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
        // Vivo / iQOO
        "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
        // OnePlus — older OxygenOS
        "com.oneplus.security" to "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
        // Asus
        "com.asus.mobilemanager" to "com.asus.mobilemanager.autostart.AutoStartActivity",
        // Letv
        "com.letv.android.letvsafe" to "com.letv.android.letvsafe.AutobootManageActivity"
    )

    @Volatile
    private var cachedAvailable: Boolean? = null

    /**
     * Whether this device has any of these pages at all.
     *
     * Cached because it is read during composition and resolving a dozen intents on every
     * recomposition is pure waste. The answer cannot change without a system update, which
     * restarts the process anyway.
     */
    fun isAvailable(context: Context): Boolean =
        cachedAvailable ?: pages(context).isNotEmpty().also { cachedAvailable = it }

    /**
     * Pages to try, in order, for "stop killing this app". The caller opens the first that
     * launches — see [Permissions.open].
     */
    fun pages(context: Context): List<Intent> {
        val candidates = autostart.map { (pkg, cls) ->
            Intent().setComponent(ComponentName(pkg, cls))
        } + miuiExtras(context)
        return candidates.filter { resolves(context, it) }
    }

    /**
     * Two more MIUI pages worth reaching, both of which take the target package as an extra.
     *
     * The permission editor is where "Display pop-up windows while running in background"
     * lives — a Xiaomi-only permission, separate from Android's own overlay grant, and a
     * common reason a lock screen never appears. HiddenAppsConfigActivity is the per-app
     * "battery saver: no restrictions" toggle.
     */
    private fun miuiExtras(context: Context): List<Intent> = listOf(
        Intent()
            .setComponent(
                ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.permissions.PermissionsEditorActivity"
                )
            )
            .putExtra("extra_pkgname", context.packageName),
        Intent()
            .setComponent(
                ComponentName(
                    "com.miui.powerkeeper",
                    "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"
                )
            )
            .putExtra("package_name", context.packageName)
            .putExtra("package_label", context.packageName)
    )

    @Suppress("DEPRECATION")
    private fun resolves(context: Context, intent: Intent): Boolean = runCatching {
        context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null
    }.getOrDefault(false)

    /**
     * The phone's brand, for telling the user which vendor is doing this to them. Null-safe
     * because [Build] is a stub with null fields under unit tests.
     */
    val vendorName: String
        get() = Build.MANUFACTURER
            ?.takeIf { it.isNotBlank() }
            ?.replaceFirstChar { it.uppercase() }
            ?: "Your phone"
}
