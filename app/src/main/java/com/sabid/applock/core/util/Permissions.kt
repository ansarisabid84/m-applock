package com.sabid.applock.core.util

import android.app.AppOpsManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import androidx.core.content.ContextCompat
import com.sabid.applock.service.AppLockAccessibilityService

/**
 * The grants Android will only let the user flip inside Settings.
 *
 * [AUTOSTART] is the odd one out: it is not an Android permission at all but the vendor
 * allow-list behind [OemSettings], and unlike the rest there is no way to read whether it
 * is on. It is in this enum anyway so that "tap to fix" has one type of destination
 * instead of two.
 */
enum class SpecialGrant { ACCESSIBILITY, OVERLAY, USAGE_ACCESS, BATTERY, AUTOSTART }

/** Runtime permissions we ask for with a normal system dialog. */
enum class RuntimeGrant(val permission: String) {
    NOTIFICATIONS(android.Manifest.permission.POST_NOTIFICATIONS),
    CAMERA(android.Manifest.permission.CAMERA)
}

/**
 * A point-in-time read of every grant AppLock cares about.
 *
 * [essentialsGranted] is the only question that matters for whether locking works at
 * all; everything else degrades a feature rather than breaking the app.
 */
data class PermissionState(
    val accessibility: Boolean = false,
    val overlay: Boolean = false,
    val usageAccess: Boolean = false,
    val notifications: Boolean = false,
    val camera: Boolean = false,
    val batteryExempt: Boolean = false
) {
    val essentialsGranted: Boolean get() = accessibility && overlay
}

/**
 * AppLock needs grants that no runtime dialog can request: accessibility, overlay and
 * usage access. Each has to be flipped by hand in Settings.
 *
 * Every navigation here is failure-tolerant. The specific Settings actions below are
 * not guaranteed to resolve — `ACTION_MANAGE_OVERLAY_PERMISSION` and
 * `ACTION_USAGE_ACCESS_SETTINGS` are routinely missing on emulators and on OEM ROMs
 * that ship their own permission manager. An unguarded startActivity there throws
 * ActivityNotFoundException and takes the whole app down, which is exactly the crash
 * users hit while working through onboarding. Each call now degrades to the app's
 * details page instead, and reports whether anything opened.
 */
object Permissions {

    // ---- reads --------------------------------------------------------------

    fun hasOverlay(context: Context): Boolean =
        runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)

    fun hasAccessibility(context: Context): Boolean = runCatching {
        val component = ComponentName(context, AppLockAccessibilityService::class.java)
        // ROMs are inconsistent about which form they persist, so accept either.
        val candidates = setOf(component.flattenToString(), component.flattenToShortString())
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        while (splitter.hasNext()) {
            val entry = splitter.next()
            if (candidates.any { it.equals(entry, ignoreCase = true) }) return true
        }
        false
    }.getOrDefault(false)

    fun hasUsageAccess(context: Context): Boolean = runCatching {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), context.packageName
            )
        }
        mode == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    fun isBatteryOptimised(context: Context): Boolean = runCatching {
        val pm = context.getSystemService(PowerManager::class.java) ?: return false
        !pm.isIgnoringBatteryOptimizations(context.packageName)
    }.getOrDefault(false)

    fun hasRuntime(context: Context, grant: RuntimeGrant): Boolean {
        // POST_NOTIFICATIONS does not exist before Android 13; treat it as granted so
        // the gate does not demand something the platform cannot give.
        if (grant == RuntimeGrant.NOTIFICATIONS &&
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
        ) {
            return true
        }
        return runCatching {
            ContextCompat.checkSelfPermission(context, grant.permission) ==
                PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }

    fun hasCameraHardware(context: Context): Boolean = runCatching {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT)
    }.getOrDefault(false)

    fun has(context: Context, grant: SpecialGrant): Boolean = when (grant) {
        SpecialGrant.ACCESSIBILITY -> hasAccessibility(context)
        SpecialGrant.OVERLAY -> hasOverlay(context)
        SpecialGrant.USAGE_ACCESS -> hasUsageAccess(context)
        SpecialGrant.BATTERY -> !isBatteryOptimised(context)
        // Vendor allow-lists expose no read API of any kind. Reporting "held" is the only
        // honest option: we must never block anything on a fact we cannot check.
        SpecialGrant.AUTOSTART -> true
    }

    fun snapshot(context: Context): PermissionState = PermissionState(
        accessibility = hasAccessibility(context),
        overlay = hasOverlay(context),
        usageAccess = hasUsageAccess(context),
        notifications = hasRuntime(context, RuntimeGrant.NOTIFICATIONS),
        camera = hasRuntime(context, RuntimeGrant.CAMERA),
        batteryExempt = !isBatteryOptimised(context)
    )

    // ---- navigation ---------------------------------------------------------

    /**
     * Sends the user to the right Settings page for [grant].
     *
     * Returns false when nothing could be opened, so the caller can show a "open
     * Settings manually" hint rather than appearing to do nothing.
     */
    fun open(context: Context, grant: SpecialGrant): Boolean {
        val packageUri = Uri.parse("package:${context.packageName}")
        val attempts = when (grant) {
            SpecialGrant.ACCESSIBILITY -> listOf(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            )

            SpecialGrant.OVERLAY -> listOf(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri),
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            )

            SpecialGrant.USAGE_ACCESS -> listOf(
                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, packageUri),
                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            )

            SpecialGrant.BATTERY -> listOf(
                @Suppress("BatteryLife")
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri),
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            )

            SpecialGrant.AUTOSTART -> OemSettings.pages(context)
        }

        // Always keep the app-details page as the last resort: every grant above is
        // reachable from there on every ROM.
        val chain = attempts + Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
        return chain.any { launch(context, it) }
    }

    fun openAppDetails(context: Context): Boolean = launch(
        context,
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")
        )
    )

    private fun launch(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        // Some ROMs guard their own Settings activities with a signature permission.
        false
    } catch (_: RuntimeException) {
        // Nothing about opening a Settings page is worth crashing the app over.
        false
    }
}
