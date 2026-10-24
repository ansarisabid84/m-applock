package com.sabid.applock.ui.lock

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.sabid.applock.AppLockApp
import com.sabid.applock.service.FailedAttempt
import com.sabid.applock.service.LockOverlay
import com.sabid.applock.service.LockPresenter
import com.sabid.applock.service.LockSession
import com.sabid.applock.service.TaskShield
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The screen a user meets when they open a protected app.
 *
 * Contract with the rest of the system:
 *  - It is never in recents (excludeFromRecents + noHistory semantics).
 *  - FLAG_SECURE stops the PIN pad appearing in screenshots or the recents thumbnail.
 *  - Leaving without unlocking finishes the activity, so the next launch re-challenges.
 *  - Back does not "cancel" into the protected app; it sends the user to the launcher.
 */
class LockActivity : FragmentActivity() {

    private var unlocked = false
    private val targetPackage: String
        get() = intent.getStringExtra(EXTRA_TARGET_PACKAGE) ?: SELF_TARGET

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!com.sabid.applock.BuildConfig.DEBUG) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goHome()
        })

        setContent {
            val settingsState = AppLockApp.get().settings
            LockGate(
                activity = this,
                targetPackage = targetPackage,
                settingsStore = settingsState,
                onUnlocked = ::succeed,
                onGiveUp = ::goHome,
                onFailedAttempt = ::recordFailure
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // Proof of life for LockPresenter. A background activity start that the system
        // silently refuses never reaches here, which is how LockEngine tells the
        // difference between "locked" and "we asked and nothing happened".
        LockPresenter.confirmShown(targetPackage)

        // An overlay is up in front of us, so nothing we draw can be seen. Only reachable
        // via LockEngine.escalateToActivity, which hides it first — but a stale window
        // here would be indistinguishable from a frozen phone, so never assume.
        LockOverlay.hide()

        // We are running from a cold background start, which means such starts are not
        // blocked after all. Clear the learned flag so the next challenge gets an activity
        // — and with it biometrics — rather than going straight to the overlay; one
        // transient refusal should not cost fingerprint unlock forever.
        //
        // Deliberately not done for an escalated start. Those run while an overlay is
        // visible and succeed under a different exemption entirely, so treating them as
        // evidence would clear the flag every time the user taps the fingerprint button
        // and put the 1.5s "protected app on screen" delay back on every single launch.
        if (!intent.getBooleanExtra(EXTRA_FROM_OVERLAY, false)) {
            lifecycleScope.launch {
                val store = AppLockApp.get().settings
                if (store.current().activityStartBlocked) store.setActivityStartBlocked(false)
            }
        }
    }

    private fun succeed() {
        unlocked = true
        lifecycleScope.launch {
            LockSession.grant(targetPackage)
            com.sabid.applock.service.LockHistory.record(
                com.sabid.applock.core.data.LockEvent.KIND_UNLOCK, targetPackage
            )
            LockPresenter.dismiss()
            withContext(Dispatchers.Main) {
                if (targetPackage == SELF_TARGET) {
                    setResult(RESULT_OK)
                }
                // Reachable when the overlay escalated to here for biometrics after the
                // shield had already closed the app's task, in which case there is nothing
                // behind this activity to return to. A no-op otherwise. Started before
                // finishing, while this activity is still foreground and so still permitted
                // to launch another app.
                TaskShield.reopen(applicationContext, targetPackage)
                finish()
                overridePendingTransition(0, 0)
            }
        }
    }

    /**
     * On the application scope, not [lifecycleScope]. Someone who has just guessed wrong
     * typically closes the lock screen immediately, and a lifecycle-scoped job would be
     * cancelled on the way out — losing the capture triggered by that very attempt.
     */
    private fun recordFailure(attempts: Int) {
        AppLockApp.get().appScope.launch {
            FailedAttempt.handle(applicationContext, targetPackage, attempts)
        }
    }

    private fun goHome() {
        LockPresenter.dismiss()
        if (targetPackage == SELF_TARGET) {
            finishAffinity()
            return
        }
        // Finish first, then evict. This activity is on top of the protected app, so it
        // would otherwise swallow the back press TaskShield uses to empty the app's task —
        // the same ordering constraint the overlay has, for the same reason.
        finish()
        overridePendingTransition(0, 0)
        TaskShield.evict(applicationContext, targetPackage)
    }

    override fun onPause() {
        super.onPause()
        // If the user navigated away mid-challenge, drop the challenge so the app is
        // re-locked rather than left in a half-authenticated state.
        if (!unlocked && !isChangingConfigurations) {
            LockPresenter.dismiss()
            finish()
        }
    }

    companion object {
        const val EXTRA_TARGET_PACKAGE = "target_package"
        const val EXTRA_FROM_OVERLAY = "from_overlay"
        const val SELF_TARGET = "com.sabid.applock"
    }
}
