package com.sabid.applock.ui.onboarding

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sabid.applock.AppLockApp
import com.sabid.applock.core.util.Permissions
import com.sabid.applock.core.util.RuntimeGrant
import com.sabid.applock.core.util.SpecialGrant
import com.sabid.applock.service.LockGuardService
import com.sabid.applock.ui.permissions.GrantRow
import com.sabid.applock.ui.permissions.SensitiveGrant
import com.sabid.applock.ui.permissions.rememberConsent
import com.sabid.applock.ui.permissions.rememberAccessibilityDisclosure
import com.sabid.applock.ui.permissions.LocalPermissionRequester
import com.sabid.applock.ui.permissions.rememberPermissionState
import kotlinx.coroutines.launch

private enum class Step { WELCOME, PERMISSIONS, DONE }

/**
 * First-run flow.
 *
 * Every special grant is explained in the user's own terms before we send them to
 * Settings for it. Users abandon security apps at the accessibility prompt when it
 * arrives with no explanation, and rightly so — that dialog is scary on purpose.
 *
 * No PIN and no recovery question here, and the omission is deliberate. Inventing a secret is
 * the one thing a new user cannot yet see the point of: they have not chosen an app to lock, so
 * the question arrives as a toll gate rather than a decision. It is asked instead at the moment
 * it means something — the first time a lock is switched on — where the choice includes using
 * the phone's own screen lock and can be declined. See
 * [com.sabid.applock.ui.lock.UnlockSetupPrompt]. The recovery question follows the secret, in
 * [com.sabid.applock.ui.settings.ChangeSecretScreen], because a recovery answer for a secret
 * that does not exist yet protects nothing.
 */
@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = AppLockApp.get().settings

    var step by remember { mutableStateOf(Step.WELCOME) }
    // Re-read grant state on every resume, i.e. each time the user comes back from a
    // Settings page. The runtime-permission launchers are owned by the Activity rather
    // than registered here — see RuntimePermissionRequester.
    val permissionState = rememberPermissionState()
    val requester = LocalPermissionRequester.current
    var settingsPageMissing by remember { mutableStateOf(false) }
    val consent = rememberConsent()

    // The disclosure is a popup raised only when the user taps the "Detect the app in front"
    // row, and it is the last thing they see before the Accessibility page opens. Agree opens
    // that page; "No thanks" closes the popup and changes nothing. Hoisted here so it survives
    // the recomposition that opening a Settings page causes.
    val askAccessibility = rememberAccessibilityDisclosure { opened ->
        settingsPageMissing = !opened
    }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(40.dp))

            when (step) {
                Step.WELCOME -> {
                    Header("AppLock", "A lock screen in front of the apps you choose. Everything stays on this phone.")
                    Spacer(Modifier.height(28.dp))
                    Promise("No ads, ever", "There is no ad SDK in this build.")
                    Promise("No account, no cloud", "Nothing is uploaded. There is no server to upload to.")
                    Promise("Your PIN is not stored", "Only a salted PBKDF2 hash of it is.")
                    Promise("Vault files are encrypted", "AES-256-GCM with a key held in your phone's secure hardware.")
                    Spacer(Modifier.height(32.dp))
                    Primary("Get started") { step = Step.PERMISSIONS }
                }

                Step.PERMISSIONS -> {
                    Header(
                        "Two switches to flip",
                        "AppLock can't lock anything without these, and Android only lets you grant them by hand."
                    )
                    Spacer(Modifier.height(20.dp))

                    GrantRow(
                        title = "Detect the app in front",
                        why = "Lets AppLock notice the moment a protected app opens. It reads only the app's name — never what's on your screen.",
                        granted = permissionState.accessibility,
                        required = true
                    ) { askAccessibility() }

                    GrantRow(
                        title = "Display over other apps",
                        why = "This is the permission the lock screen is drawn with. Without " +
                            "it AppLock can only send you back to your home screen — it " +
                            "cannot ask for your PIN.",
                        granted = permissionState.overlay,
                        required = true
                    ) {
                        consent(SensitiveGrant.OVERLAY) { settingsPageMissing = !Permissions.open(context, SpecialGrant.OVERLAY) }
                    }

                    GrantRow(
                        title = "Usage access",
                        why = "A backup way to spot app launches on phones that kill the accessibility service.",
                        granted = permissionState.usageAccess,
                        required = false
                    ) {
                        consent(SensitiveGrant.USAGE_ACCESS) { settingsPageMissing = !Permissions.open(context, SpecialGrant.USAGE_ACCESS) }
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        GrantRow(
                            title = "Notifications",
                            why = "Shows the small badge confirming protection is running, and intruder alerts.",
                            granted = permissionState.notifications,
                            required = false
                        ) {
                            consent(SensitiveGrant.NOTIFICATIONS) { requester?.request(listOf(RuntimeGrant.NOTIFICATIONS), context) }
                        }
                    }

                    if (Permissions.hasCameraHardware(context)) {
                        GrantRow(
                            title = "Camera",
                            why = "Only used to photograph whoever fails your PIN repeatedly. Nothing else touches the camera.",
                            granted = permissionState.camera,
                            required = false
                        ) {
                            consent(SensitiveGrant.CAMERA) { requester?.request(listOf(RuntimeGrant.CAMERA), context) }
                        }
                    }

                    GrantRow(
                        title = "Ignore battery optimisation",
                        why = "Stops the system suspending AppLock in the background, which would silently stop it locking anything.",
                        granted = permissionState.batteryExempt,
                        required = false
                    ) {
                        consent(SensitiveGrant.BATTERY) { settingsPageMissing = !Permissions.open(context, SpecialGrant.BATTERY) }
                    }

                    if (settingsPageMissing) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "This phone wouldn't open that page directly. Open Settings > Apps > " +
                                "AppLock and grant it from there.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    Spacer(Modifier.height(24.dp))
                    Primary("Done", enabled = permissionState.essentialsGranted) {
                        scope.launch {
                            store.setOnboarded(true)
                            LockGuardService.start(context)
                            onFinished()
                        }
                    }
                    // Escape hatch: on ROMs where the Settings pages cannot be opened at
                    // all, requiring the grants here would trap the user in onboarding
                    // forever. PermissionGate re-asks on every app open instead.
                    if (!permissionState.essentialsGranted) {
                        TextButton(onClick = {
                            scope.launch {
                                store.setOnboarded(true)
                                LockGuardService.start(context)
                                onFinished()
                            }
                        }) { Text("Set these up later") }
                    }
                    Spacer(Modifier.height(24.dp))
                }

                Step.DONE -> onFinished()
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun Header(title: String, subtitle: String) {
    Text(title, style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onBackground)
    Spacer(Modifier.height(8.dp))
    Text(
        subtitle,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )
}

@Composable
private fun Promise(title: String, body: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Icon(
            Icons.Filled.CheckCircle, null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp).padding(top = 2.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Primary(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().height(52.dp)
    ) { Text(label, style = MaterialTheme.typography.labelLarge) }
}
