package com.sabid.applock

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.sabid.applock.core.model.ThemeSkin
import com.sabid.applock.core.util.Unlock
import com.sabid.applock.service.LockGuardService
import com.sabid.applock.ui.AppNav
import com.sabid.applock.ui.lock.LockActivity
import com.sabid.applock.ui.lock.LockGate
import com.sabid.applock.ui.onboarding.OnboardingScreen
import com.sabid.applock.ui.permissions.LocalPermissionRequester
import com.sabid.applock.ui.permissions.PermissionGate
import com.sabid.applock.ui.permissions.RuntimePermissionRequester
import com.sabid.applock.ui.theme.AppLockTheme
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {

    private lateinit var permissionRequester: RuntimePermissionRequester

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Must be registered before the Activity reaches STARTED, so it happens here
        // rather than inside a composable that may enter and leave the composition.
        permissionRequester = RuntimePermissionRequester(this)

        // AppLock's own settings are the crown jewels — never let them into a screenshot.
        if (!com.sabid.applock.BuildConfig.DEBUG) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Nothing else revived the guard on a normal app open: it was started from
        // onboarding, from BOOT_COMPLETED, and from the accessibility service, so once a
        // ROM killed the process there was no watchdog again until the next reboot.
        // Opening AppLock is the clearest signal we get that the user expects it working.
        lifecycleScope.launch {
            if (AppLockApp.get().settings.current().protectionEnabled) {
                LockGuardService.start(this@MainActivity)
            }
        }

        setContent {
            val store = AppLockApp.get().settings
            // Null until DataStore has actually produced a value. Seeding this with a
            // default AppSettings() meant `onboarded` read false for one frame and every
            // cold start flashed the onboarding screen at an already-onboarded user.
            val settings by store.flow.collectAsState(initial = null)
            var authenticated by remember { mutableStateOf(false) }

            val s = settings
            AppLockTheme(theme = s?.theme ?: ThemeSkin.MIDNIGHT) {
                CompositionLocalProvider(LocalPermissionRequester provides permissionRequester) {
                    when {
                        s == null -> Loading()

                        !s.onboarded -> OnboardingScreen(
                            onFinished = { authenticated = true }
                        )

                        // Unlock.usable, not `secret != null`. Two corrections in one: in
                        // phone-screen-lock mode there is no AppLock secret, and gating on one
                        // would leave the locked list, the vault and the intruder photos open
                        // to whoever picked the phone up; and in the reverse case — that mode
                        // chosen, phone lock since removed — gating would lock the owner out
                        // of the only screen where they could fix it.
                        Unlock.usable(this, s) && !authenticated -> LockGate(
                            activity = this,
                            targetPackage = LockActivity.SELF_TARGET,
                            settingsStore = store,
                            onUnlocked = { authenticated = true },
                            onGiveUp = { finishAffinity() }
                        )

                        else -> {
                            // Reaching the main UI means the owner is in. Without this, setting a
                            // PIN from inside AppLock made the secret usable and the gate above
                            // immediately asked for it again.
                            LaunchedEffect(Unit) { authenticated = true }
                            PermissionGate {
                                AppNav(activity = this)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Loading() {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
    }
}
