package com.sabid.applock.service

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.View
import android.view.WindowManager
import com.sabid.applock.core.util.Permissions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A plain opaque window, used to hide the screen while [TaskShield] does something the user
 * must not see.
 *
 * There is exactly one such thing: closing a locked app *after* the user has left it. Android
 * snapshots a task the moment it stops being visible and shows that snapshot in the app
 * switcher, so by the time we learn the user pressed home the picture has already been taken.
 * The only way to get rid of it is to empty the app's task — and the only way to empty a task
 * is to press back at the app while it is in front, which means bringing it in front again
 * for a moment. This is what covers that moment.
 *
 * Deliberately not [LockOverlay]: nothing here composes, takes focus, or asks for a PIN. It is
 * a black rectangle. Showing a lock screen after somebody pressed home would be a worse bug
 * than the one this fixes.
 *
 * Not focusable, because the back presses that follow have to reach the app underneath rather
 * than this window — the same constraint the lock overlay has while the shield is running. It
 * *is* touchable, so a stray tap during the half second it is up cannot reach the app behind
 * it.
 *
 * [show] and [hide] are main thread only. [awaitDrawn] may be called from anywhere.
 */
object ScreenCover {

    @Volatile
    private var view: Cover? = null

    @Volatile
    private var windows: WindowManager? = null

    /**
     * Completed by the cover's first frame. Held separately from [view] so that a background
     * coroutine can wait on it without touching main-thread state.
     */
    @Volatile
    private var drawn: CompletableDeferred<Unit>? = null

    val isShowing: Boolean get() = view != null

    /**
     * Puts the cover up and reports whether it worked. False means no overlay grant, and the
     * caller must not go on to front the app — that would put its contents on screen.
     *
     * Returning true means the window was *accepted*, not that it is on screen yet; that takes
     * a frame or two. Callers that care — and the one caller does, see [awaitDrawn] — must wait.
     */
    fun show(context: Context): Boolean {
        if (view != null) return true
        if (!Permissions.hasOverlay(context)) return false

        val windowManager = context.getSystemService(WindowManager::class.java) ?: return false
        val signal = CompletableDeferred<Unit>()
        val cover = Cover(context, signal)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_SECURE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                // Reaches under the status and navigation bars. Without it the app being
                // fronted shows through the cutouts at the top and bottom of the screen.
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE
        )

        return runCatching { windowManager.addView(cover, params) }
            .onSuccess { view = cover; windows = windowManager; drawn = signal }
            .onFailure { LockLog.w("could not cover the screen: ${it.message}") }
            .isSuccess
    }

    /**
     * Waits until the cover has actually painted, and reports whether it did within [timeoutMs].
     *
     * This is not cosmetic, and it is the fix for a measured failure. Bringing another app to
     * the front from the background is refused on this class of ROM unless the caller has a
     * *visible* window — the system logs its verdict, and with the request sent immediately
     * after `addView` the verdict was `BAL_ALLOW_SAW_PERMISSION` (the weaker "it holds the
     * overlay permission" exemption) followed by `result code=102`, START_ABORTED: the app
     * never came forward and the departed app kept its recents card. Waiting for the first
     * frame is what turns that into `BAL_ALLOW_VISIBLE_WINDOW`, which is honoured.
     *
     * False means the cover never painted, and the caller must treat that exactly like a
     * missing grant: do not front the app, because there may be nothing hiding it.
     */
    suspend fun awaitDrawn(timeoutMs: Long): Boolean {
        val signal = drawn ?: return false
        return withTimeoutOrNull(timeoutMs) { signal.await() } != null
    }

    /** Takes the cover down. Safe to call when nothing is up. */
    fun hide() {
        val cover = view ?: return
        val windowManager = windows
        view = null
        windows = null
        drawn = null
        runCatching { windowManager?.removeViewImmediate(cover) }
    }

    /**
     * The cover itself, which is a black rectangle that reports when it has drawn one.
     *
     * A view-tree draw listener would do the same job, but it has to be unregistered from
     * outside the draw pass or it throws, and this needs no unregistering at all.
     */
    private class Cover(
        context: Context,
        private val drawn: CompletableDeferred<Unit>
    ) : View(context) {

        init {
            setBackgroundColor(Color.BLACK)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            drawn.complete(Unit)
        }
    }
}
