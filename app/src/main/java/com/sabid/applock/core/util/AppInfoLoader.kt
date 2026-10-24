package com.sabid.applock.core.util

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.sabid.applock.core.model.InstalledApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object AppInfoLoader {

    /**
     * Launchable apps only. Enumerating every installed package would surface
     * hundreds of invisible system components the user cannot meaningfully lock.
     */
    suspend fun loadLaunchable(context: Context, locked: Set<String>): List<InstalledApp> =
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(intent, 0)
                .asSequence()
                .mapNotNull { it.activityInfo?.applicationInfo }
                .distinctBy { it.packageName }
                .filter { it.packageName != context.packageName }
                .map { info ->
                    InstalledApp(
                        packageName = info.packageName,
                        label = runCatching { pm.getApplicationLabel(info).toString() }
                            .getOrDefault(info.packageName),
                        isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                        isLocked = info.packageName in locked
                    )
                }
                .sortedWith(compareByDescending<InstalledApp> { it.isLocked }.thenBy { it.label.lowercase() })
                .toList()
        }

    /** Sensitive system surfaces worth suggesting on first run. */
    val recommended = setOf(
        "com.android.settings",
        "com.android.vending",
        "com.google.android.gm",
        "com.whatsapp",
        "com.android.chrome"
    )

    suspend fun icon(context: Context, pkg: String): ImageBitmap? = withContext(Dispatchers.IO) {
        runCatching { context.packageManager.getApplicationIcon(pkg).toImageBitmap() }.getOrNull()
    }

    suspend fun label(context: Context, pkg: String): String = withContext(Dispatchers.IO) {
        runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
    }

    private fun Drawable.toImageBitmap(): ImageBitmap {
        if (this is BitmapDrawable && bitmap != null) return bitmap.asImageBitmap()
        val width = intrinsicWidth.coerceAtLeast(1)
        val height = intrinsicHeight.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        setBounds(0, 0, canvas.width, canvas.height)
        draw(canvas)
        return bmp.asImageBitmap()
    }
}
