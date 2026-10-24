package com.sabid.applock.service

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.sabid.applock.AppLockApp
import com.sabid.applock.R
import kotlinx.coroutines.launch

/** One-tap home screen toggle for protection. */
class LockWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        // Read the cached snapshot rather than the DataStore flow: a broadcast receiver
        // runs on the main thread with a hard deadline, and runBlocking on the flow reads
        // from disk on first access, which ANRs.
        render(context, manager, ids, AppLockApp.get().snapshot.protectionEnabled)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TOGGLE) {
            super.onReceive(context, intent)
            return
        }
        // goAsync keeps the receiver alive while the DataStore write completes, which is
        // the supported way to do I/O from a receiver. finish() must run on every path or
        // the system logs a leak and eventually kills the process.
        val pending = goAsync()
        val app = AppLockApp.get()
        app.appScope.launch {
            try {
                val next = !app.settings.current().protectionEnabled
                app.settings.setProtection(next)
                if (next) LockGuardService.start(context) else LockSession.revokeAll()
                // Draw `next` directly instead of re-broadcasting an update. The cached
                // snapshot has not necessarily observed the write yet, so a round trip
                // through onUpdate could paint the pre-toggle state.
                val manager = AppWidgetManager.getInstance(context)
                render(context, manager, widgetIds(context, manager), next)
            } finally {
                pending.finish()
            }
        }
    }

    private fun render(context: Context, manager: AppWidgetManager, ids: IntArray, enabled: Boolean) {
        ids.forEach { id ->
            val views = RemoteViews(context.packageName, R.layout.widget_lock).apply {
                setTextViewText(R.id.widget_state, if (enabled) "ON" else "OFF")
                setInt(
                    R.id.widget_icon, "setColorFilter",
                    if (enabled) 0xFFC79A46.toInt() else 0xFF6B7280.toInt()
                )
                setOnClickPendingIntent(R.id.widget_root, togglePendingIntent(context))
            }
            runCatching { manager.updateAppWidget(id, views) }
        }
    }

    private fun togglePendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, LockWidgetProvider::class.java).setAction(ACTION_TOGGLE)
        return PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    companion object {
        const val ACTION_TOGGLE = "com.sabid.applock.WIDGET_TOGGLE"

        private fun widgetIds(context: Context, manager: AppWidgetManager): IntArray =
            runCatching {
                manager.getAppWidgetIds(ComponentName(context, LockWidgetProvider::class.java))
            }.getOrDefault(IntArray(0))

        /** Asks the system to redraw every placed widget. Safe from any thread. */
        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = widgetIds(context, manager)
            if (ids.isEmpty()) return
            context.sendBroadcast(
                Intent(context, LockWidgetProvider::class.java)
                    .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            )
        }
    }
}
