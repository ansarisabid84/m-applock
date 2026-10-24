package com.sabid.applock.service

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.sabid.applock.AppLockApp
import com.sabid.applock.R
import com.sabid.applock.core.data.LockEvent
import com.sabid.applock.core.util.Permissions
import com.sabid.applock.ui.lock.LockGate
import kotlinx.coroutines.launch

/**
 * The lock screen as a window we add ourselves, rather than an activity we ask Android to
 * launch for us.
 *
 * This exists because the activity route does not work. Since Android 10, starting an
 * activity from the background is refused unless the process holds one of a short list of
 * exemptions — and on many real devices, holding SYSTEM_ALERT_WINDOW is not enough. Worse,
 * a refused start does not throw: `startActivity` returns normally, logs a line into
 * logcat, and no window appears. The result on the user's phone was a protected app
 * opening straight through with nothing reported anywhere, and then — once
 * [LockEngine] learned to detect the silence — the protected app being bounced to the
 * launcher a second or two after opening. Neither is a lock screen.
 *
 * Adding a window with [WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY] needs no
 * background-start exemption at all. If the user has granted "display over other apps",
 * this cannot be silently suppressed: `addView` either works or throws.
 *
 * The cost is BiometricPrompt, which requires a FragmentActivity and therefore cannot run
 * here. The fingerprint button instead calls back into [LockEngine.escalateToActivity],
 * which hides this window, tries the activity, and puts this window back if the activity
 * never arrives. So biometrics still work wherever they can, and the PIN pad always works.
 *
 * Everything in here runs on the main thread. Both [show] and [hide] assume it.
 */
object LockOverlay {

    /**
     * How long a lock window has to have been up before an outside signal is allowed to take
     * it down.
     *
     * Belt and braces with [TaskShield.isShielding]. Emptying the app's task makes the
     * launcher visible, which produces exactly the window change that [leaveForHome] reads as
     * "the user went home", and the shield's own grace window is the primary defence against
     * that. This is the second one, for the case where the ROM delivers that change late.
     */
    private const val MIN_ON_SCREEN_MS = 1_000L

    @Volatile
    private var host: Host? = null

    val isShowing: Boolean get() = host != null

    /** The package a lock window is currently guarding, or null when nothing is up. */
    val target: String? get() = host?.targetPackage

    /** True when a lock window is up and it is guarding exactly [packageName]. */
    fun isFor(packageName: String): Boolean = host?.targetPackage == packageName

    /**
     * Puts the lock screen over whatever is on screen and returns whether it worked.
     *
     * False means the window could not be added — almost always a missing or revoked
     * "display over other apps" grant. The caller must treat that as a failure to lock.
     *
     * Pass `focusable = false` when [TaskShield] is about to inject a back press to empty the
     * protected app's task. A focusable window receives the key itself — [BackAwareRoot] would
     * read it as the user declining — so the shield needs the key to pass through to the app
     * underneath. The window still covers the app and still takes touches; only keyboard focus
     * is withheld, and [grantFocus] restores it once the shield is done.
     */
    fun show(context: Context, packageName: String, focusable: Boolean = true): Boolean {
        if (isFor(packageName)) return true
        hide()

        // Checked rather than assumed: the grant is revocable at any time, and finding out
        // via a BadTokenException in a service is a worse way to learn it.
        if (!Permissions.hasOverlay(context)) return false

        val fresh = Host(context.applicationContext, packageName, focusable)
        return runCatching { fresh.attach() }
            .onSuccess { host = fresh }
            .onFailure { runCatching { fresh.detach() } }
            .isSuccess
    }

    /**
     * Gives the lock window keyboard focus, after it was added without it.
     *
     * Must run once the shield's injected back presses are done, and must run even if the
     * shield failed: without focus the PIN pad takes no key events and the recovery-answer
     * field can never be typed into, so skipping this would leave a lock screen nobody can
     * get past. No-op when the window is already focusable or already gone.
     */
    fun grantFocus() {
        host?.grantFocus()
    }

    /** Takes the lock window down. Safe to call when nothing is showing. */
    fun hide() {
        release(host ?: return)
    }

    /**
     * The user pressed home while the lock screen was up: take it down and leave the app
     * locked in the background.
     *
     * This has to be handled explicitly, and that is the whole problem with presenting a lock
     * screen as a window rather than an activity. An activity is torn down by the system when
     * the user goes home — [com.sabid.applock.ui.lock.LockActivity] finishes itself in
     * `onPause`. A `TYPE_APPLICATION_OVERLAY` window sits at a layer above every activity,
     * including the launcher, so pressing home moves the app out from under it and leaves the
     * lock screen floating over the home screen with nothing to lock. It looked like a frozen
     * phone, and it is what the user reported.
     *
     * Three separate signals arrive here, because no single one of them is reliable on every
     * device or navigation style:
     *
     *  - [LockEngine.onForeground], when the launcher genuinely comes forward. Covers the case
     *    where the protected app was still behind us.
     *  - `ACTION_CLOSE_SYSTEM_DIALOGS` with `reason=homekey`/`recentapps`, which the system
     *    broadcasts on a home or recents press. Covers the harder case: once the shield has
     *    emptied the app's task the launcher is *already* in front, so pressing home changes
     *    nothing that could be observed as a window change.
     *  - [KeyEvent.KEYCODE_HOME] on our own root view, for devices that deliver it.
     *
     * All three are idempotent — the first one to arrive wins and the rest find nothing up.
     *
     * The app is deliberately *not* reopened: the user asked to go home. It is relocked
     * instead, so opening it again means meeting the lock screen again, and [TaskShield.evict]
     * gets it out of the way. Main thread only.
     */
    fun leaveForHome(context: Context, reason: String) {
        val current = host ?: return
        if (SystemClock.elapsedRealtime() - current.shownAt < MIN_ON_SCREEN_MS) return

        val pkg = current.targetPackage
        LockLog.d("lock screen for $pkg dismissed: $reason")
        LockPresenter.dismiss()
        LockSession.revoke(pkg)
        release(current)
        // No back press: the launcher is in front by now, or on its way, and a back press
        // would be delivered to it rather than to the app we are trying to get rid of.
        TaskShield.evict(context, pkg, allowBack = false)
    }

    /**
     * Removes [expected], but only if it is still the window that is up.
     *
     * The guard matters because the two self-initiated exits are posted rather than
     * immediate, and a posted teardown that fired after a *different* window had replaced
     * this one would take the wrong lock screen down.
     */
    private fun release(expected: Host) {
        if (host !== expected) return
        host = null
        runCatching { expected.detach() }
    }

    /**
     * A LifecycleOwner, ViewModelStoreOwner and SavedStateRegistryOwner rolled into one,
     * because a Compose tree will not compose without all three on its view tree and a
     * bare window has no activity to supply them.
     */
    private class Host(
        private val appContext: Context,
        val targetPackage: String,
        private var focusable: Boolean
    ) : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

        private val registry = LifecycleRegistry(this)
        private val savedState = SavedStateRegistryController.create(this)
        private val store = ViewModelStore()

        override val lifecycle: Lifecycle get() = registry
        override val viewModelStore: ViewModelStore get() = store
        override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

        private val windowManager =
            appContext.getSystemService(WindowManager::class.java)

        // The theme wrapper matters: ComposeView resolves a handful of view attributes
        // through its context, and the application context carries no theme.
        private val themed = ContextThemeWrapper(appContext, R.style.Theme_AppLock)

        private val root = BackAwareRoot(
            themed,
            onBack = { giveUp() },
            onHome = { leaveForHome(appContext, "home key on the lock window") }
        )
        private val composeView = ComposeView(themed)

        /** When this window went up, for [MIN_ON_SCREEN_MS]. */
        var shownAt = 0L; private set

        /**
         * The system's "close anything you have open, the user pressed a navigation button"
         * broadcast, which is the only notice we get of a home press that does not move any
         * window. See [leaveForHome].
         */
        private val navigationWatcher = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val reason = intent.getStringExtra(REASON_EXTRA)
                if (reason == REASON_HOME || reason == REASON_RECENTS) {
                    leaveForHome(appContext, "system reported $reason")
                }
            }
        }

        fun attach() {
            savedState.performRestore(null)
            registry.currentState = Lifecycle.State.CREATED

            // On the ROOT view, not on the ComposeView, and that distinction is the whole
            // ballgame. When a ComposeView attaches it asks for a window recomposer, and
            // Compose looks for the owners on the top-most view of the window — then
            // searches *upward* from there. Tagging only the child left the root with
            // nothing above it to find, so every attach threw "ViewTreeLifecycleOwner not
            // found" from ViewRootImpl's traversal: on the main thread, one frame after
            // addView returned, and therefore nowhere any runCatching could reach it. The
            // process died, and it took the accessibility service down with it — which is
            // what all the "your phone stopped app detection" reports actually were.
            // Children inherit by walking up, so the root is the only place this belongs.
            root.setViewTreeLifecycleOwner(this)
            root.setViewTreeViewModelStoreOwner(this)
            root.setViewTreeSavedStateRegistryOwner(this)

            composeView.setContent {
                LockGate(
                    activity = null,
                    targetPackage = targetPackage,
                    settingsStore = AppLockApp.get().settings,
                    onUnlocked = ::succeed,
                    onGiveUp = ::giveUp,
                    onFailedAttempt = ::recordFailure,
                    onBiometricRequest = { LockEngine.escalateToActivity(appContext, targetPackage) }
                )
            }
            root.addView(
                composeView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )

            windowManager.addView(root, params())
            shownAt = SystemClock.elapsedRealtime()

            // Exported, because this broadcast comes from the system and a NOT_EXPORTED
            // registration is not guaranteed to receive it on every ROM. The worst another app
            // could do by faking it is dismiss a lock screen and leave the app it was guarding
            // locked and pushed to the background, which is a failure in the safe direction.
            ContextCompat.registerReceiver(
                appContext,
                navigationWatcher,
                IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS),
                ContextCompat.RECEIVER_EXPORTED
            )

            // Only now: the window recomposer created on attach stays parked until the
            // lifecycle it was given reaches STARTED.
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun detach() {
            runCatching { appContext.unregisterReceiver(navigationWatcher) }
            runCatching { windowManager.removeViewImmediate(root) }
            registry.currentState = Lifecycle.State.DESTROYED
            store.clear()
        }

        /**
         * Restores keyboard focus. Called once [TaskShield] has finished injecting back
         * presses, since those had to reach the app rather than this window.
         */
        fun grantFocus() {
            if (focusable) return
            focusable = true
            runCatching { windowManager.updateViewLayout(root, params()) }
                .onFailure { LockLog.w("could not focus the lock window: ${it.message}") }
        }

        private fun params() = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // FLAG_SECURE keeps the PIN out of screenshots and the recents thumbnail, exactly
            // as the activity did. FLAG_NOT_FOCUSABLE is the temporary state: touches are
            // still delivered without it, but key events are not, so the keypad and the
            // recovery-answer field stay dead until [grantFocus] clears it. See [show].
            (if (com.sabid.applock.BuildConfig.DEBUG) 0 else WindowManager.LayoutParams.FLAG_SECURE) or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        private fun succeed() {
            LockSession.grant(targetPackage)
            LockHistory.record(LockEvent.KIND_UNLOCK, targetPackage)
            LockPresenter.dismiss()
            root.post {
                // Ordered before the teardown on purpose. Starting another app's activity from
                // the background is refused on many ROMs, and a visible window of ours is one
                // of the few things that lifts that restriction — so the request goes out
                // while this window is still attached. A no-op unless the shield actually
                // closed the app, in which case there is nothing underneath to uncover.
                TaskShield.reopen(appContext, targetPackage)
                release(this)
            }
        }

        /**
         * Declining the challenge. Unlike the activity, removing this window reveals the
         * protected app rather than leaving it behind us, so this has to actively move the
         * user off it — and then get the app out of recents, which is [TaskShield]'s job.
         *
         * The window has to come down *before* the eviction rather than after, which is the
         * opposite of what it used to do. Eviction starts by injecting a back press, and
         * this overlay is focusable, so a back arriving while it is still up would be
         * delivered to [BackAwareRoot] and land straight back in here. Both steps run in the
         * same posted runnable, so the main thread never yields between them and the app
         * underneath gets no frame of its own.
         *
         * Posted rather than immediate because both exits are reached from a Compose click
         * handler — that is, from the middle of dispatching a touch event to the very view
         * hierarchy being removed.
         */
        private fun giveUp() {
            LockPresenter.dismiss()
            root.post {
                release(this)
                TaskShield.evict(appContext, targetPackage)
            }
        }

        private fun recordFailure(attempts: Int) {
            AppLockApp.get().appScope.launch {
                FailedAttempt.handle(appContext, targetPackage, attempts)
            }
        }
    }

    /**
     * Back has to be handled by hand here. An activity gets
     * OnBackPressedDispatcher; a raw window just receives the key, and if we ignore it
     * the system passes it to the app behind us — which would dismiss our lock screen's
     * *target*, not our lock screen.
     */
    @SuppressLint("ViewConstructor")
    private class BackAwareRoot(
        context: Context,
        private val onBack: () -> Unit,
        private val onHome: () -> Unit
    ) : FrameLayout(context) {

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) onBack()
                return true
            }
            // Home and the app-switch key are handled by the window manager long before an
            // app window sees them, so this is a bonus path rather than the one to rely on —
            // see the other two signals in [leaveForHome]. Never consumed: whatever the system
            // was going to do with home, it should still do.
            if (event.keyCode == KeyEvent.KEYCODE_HOME || event.keyCode == KeyEvent.KEYCODE_APP_SWITCH) {
                if (event.action == KeyEvent.ACTION_UP) onHome()
                return false
            }
            return super.dispatchKeyEvent(event)
        }
    }

    private const val REASON_EXTRA = "reason"
    private const val REASON_HOME = "homekey"
    private const val REASON_RECENTS = "recentapps"
}
