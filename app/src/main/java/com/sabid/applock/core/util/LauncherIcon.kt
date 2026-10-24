package com.sabid.applock.core.util

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Makes sure AppLock has a home screen icon.
 *
 * This is migration code, and it is the whole cost of removing the old "disguise the icon"
 * feature. That feature worked by keeping exactly one of three launcher aliases enabled and
 * explicitly disabling the other two, and those component states live in PackageManager, not
 * in AppLock's own settings — so they survive an app update untouched. A user who had the
 * calculator disguise on when this build installs has `Launcher.Default` recorded as
 * DISABLED and the calculator alias no longer in the manifest at all. The result would be an
 * app with no launcher entry whatsoever: still installed, still locking apps, and completely
 * unreachable.
 *
 * Resetting to [PackageManager.COMPONENT_ENABLED_STATE_DEFAULT] rather than
 * `ENABLED` on purpose — that clears the stored override and lets the manifest's
 * `android:enabled="true"` speak for itself, which keeps this from becoming a second piece
 * of state to reason about later.
 *
 * Cheap enough to run on every start: one PackageManager read, and a write only in the case
 * that actually needs fixing.
 */
object LauncherIcon {

    private const val ALIAS = "com.sabid.applock.Launcher.Default"

    fun ensureVisible(context: Context) {
        runCatching {
            val pm = context.packageManager
            val alias = ComponentName(context.packageName, ALIAS)
            if (pm.getComponentEnabledSetting(alias) == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) {
                return
            }
            pm.setComponentEnabledSetting(
                alias,
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                // Killing our own app here would take the accessibility service with it and
                // leave the phone unprotected until the user next opened AppLock.
                PackageManager.DONT_KILL_APP
            )
        }
    }
}
