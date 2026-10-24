package com.sabid.applock.ui.permissions

import com.sabid.applock.ui.theme.glass
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sabid.applock.core.util.PermissionState
import com.sabid.applock.core.util.Permissions
import com.sabid.applock.core.util.RuntimeGrant
import com.sabid.applock.core.util.SpecialGrant

/**
 * Owns the runtime-permission launchers for an Activity.
 *
 * Registration happens in the Activity's constructor/onCreate, never inside a
 * composable. `registerForActivityResult` must be called before the Activity reaches
 * STARTED; doing it from a conditionally-composed screen meant the launcher could be
 * unregistered by the time the user tapped a row, and `launch()` then threw
 * IllegalStateException. Every launch is additionally guarded, because a missing
 * permission-controller on a stripped-down ROM should not crash the app.
 */
class RuntimePermissionRequester(activity: ComponentActivity) {

    private var pending: (() -> Unit)? = null

    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val callback = pending
        pending = null
        callback?.invoke()
    }

    /** Requests every grant in [grants] that is not already held. */
    fun request(grants: List<RuntimeGrant>, context: android.content.Context, onDone: () -> Unit = {}) {
        val needed = grants
            .filter { !Permissions.hasRuntime(context, it) }
            .map { it.permission }
            .toTypedArray()

        if (needed.isEmpty()) {
            onDone()
            return
        }
        pending = onDone
        runCatching { launcher.launch(needed) }.onFailure {
            pending = null
            onDone()
        }
    }
}

val LocalPermissionRequester = staticCompositionLocalOf<RuntimePermissionRequester?> { null }

/**
 * Re-reads every grant whenever the Activity resumes, which is the only reliable
 * signal that the user came back from a Settings page.
 */
@Composable
fun rememberPermissionState(): PermissionState {
    val context = LocalContext.current
    var state by remember { mutableStateOf(Permissions.snapshot(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) state = Permissions.snapshot(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return state
}

/**
 * Blocks the app whenever a grant AppLock cannot work without has gone away, and
 * nags — once per app open — about the optional ones.
 *
 * Users revoke accessibility by accident, and aggressive ROMs revoke it on update. A
 * lock app that silently keeps running with no ability to lock anything is worse than
 * one that says so, so this re-checks on every resume rather than only at onboarding.
 */
@Composable
fun PermissionGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val requester = LocalPermissionRequester.current
    val state = rememberPermissionState()

    // Reset once per composition of the gate, i.e. once per app open.
    var optionalDismissed by remember { mutableStateOf(false) }

    val optionalMissing = !state.notifications ||
        (!state.camera && Permissions.hasCameraHardware(context)) ||
        !state.usageAccess ||
        !state.batteryExempt

    var essentialsBypassed by remember { mutableStateOf(false) }

    when {
        !state.essentialsGranted && !essentialsBypassed -> BlockingGate(
            state = state,
            requester = requester,
            onContinueAnyway = { essentialsBypassed = true }
        )

        optionalMissing && !optionalDismissed -> ReminderGate(
            state = state,
            requester = requester,
            onSkip = { optionalDismissed = true }
        )

        else -> content()
    }
}

@Composable
private fun BlockingGate(
    state: PermissionState,
    requester: RuntimePermissionRequester?,
    onContinueAnyway: () -> Unit
) {
    GateScaffold(
        title = "Nothing is locking right now",
        subtitle = "AppLock needs these two to put a lock screen in front of an app. " +
            "Without them your apps open normally.",
        alarming = true
    ) {
        EssentialRows(state)
        Spacer(Modifier.height(20.dp))
        OptionalRows(state, requester)
        Spacer(Modifier.height(24.dp))
        // Never trap the user out of their own vault and settings because a ROM revoked
        // a grant. This is a dead end otherwise — but the gate returns on the next open.
        TextButton(onClick = onContinueAnyway, modifier = Modifier.fillMaxWidth()) {
            Text("Continue without protection")
        }
    }
}

@Composable
private fun ReminderGate(
    state: PermissionState,
    requester: RuntimePermissionRequester?,
    onSkip: () -> Unit
) {
    GateScaffold(
        title = "A few things are still off",
        subtitle = "Locking works. These make it more reliable, and you can skip them.",
        alarming = false
    ) {
        OptionalRows(state, requester)
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onSkip,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) { Text("Continue", style = MaterialTheme.typography.labelLarge) }
        TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
            Text("Don't ask until next time")
        }
    }
}

@Composable
private fun GateScaffold(
    title: String,
    subtitle: String,
    alarming: Boolean,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(48.dp))
            if (alarming) {
                Icon(
                    Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(40.dp)
                )
                Spacer(Modifier.height(16.dp))
            }
            Text(
                title,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(28.dp))
            content()
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun EssentialRows(state: PermissionState) {
    val context = LocalContext.current
    var failedToOpen by remember { mutableStateOf(false) }
    // Play requires the disclosure before the request, so the row raises that rather than
    // the Settings page. See AccessibilityDisclosure.
    val askAccessibility = rememberAccessibilityDisclosure { opened -> failedToOpen = !opened }
    val consent = rememberConsent()

    GrantRow(
        title = "Detect the app in front",
        why = "Lets AppLock notice the moment a protected app opens. It reads only the " +
            "app's name — never what is on your screen.",
        granted = state.accessibility,
        required = true
    ) { askAccessibility() }

    GrantRow(
        title = "Display over other apps",
        why = "This is the permission the lock screen is drawn with. Without it AppLock " +
            "can only send you back to your home screen — it cannot ask for your PIN.",
        granted = state.overlay,
        required = true
    ) {
        consent(SensitiveGrant.OVERLAY) { failedToOpen = !Permissions.open(context, SpecialGrant.OVERLAY) }
    }

    if (failedToOpen) {
        Text(
            "This phone would not open that page directly. Open Settings > Apps > AppLock " +
                "and grant it there.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun OptionalRows(state: PermissionState, requester: RuntimePermissionRequester?) {
    val context = LocalContext.current
    val consent = rememberConsent()

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !state.notifications) {
        GrantRow(
            title = "Notifications",
            why = "Shows the badge confirming protection is running, plus intruder alerts.",
            granted = state.notifications,
            required = false
        ) {
            consent(SensitiveGrant.NOTIFICATIONS) { requester?.request(listOf(RuntimeGrant.NOTIFICATIONS), context) }
        }
    }

    if (Permissions.hasCameraHardware(context)) {
        GrantRow(
            title = "Camera",
            why = "Only used to photograph whoever fails your PIN repeatedly. Nothing else " +
                "touches the camera.",
            granted = state.camera,
            required = false
        ) {
            consent(SensitiveGrant.CAMERA) { requester?.request(listOf(RuntimeGrant.CAMERA), context) }
        }
    }

    GrantRow(
        title = "Usage access",
        why = "A backup way to spot app launches on phones that kill the accessibility service.",
        granted = state.usageAccess,
        required = false
    ) {
        consent(SensitiveGrant.USAGE_ACCESS) { Permissions.open(context, SpecialGrant.USAGE_ACCESS) }
    }

    GrantRow(
        title = "Ignore battery optimisation",
        why = "Stops the system suspending AppLock in the background, which would silently " +
            "stop it locking anything.",
        granted = state.batteryExempt,
        required = false
    ) {
        consent(SensitiveGrant.BATTERY) { Permissions.open(context, SpecialGrant.BATTERY) }
    }
}

@Composable
fun GrantRow(
    title: String,
    why: String,
    granted: Boolean,
    required: Boolean,
    onGrant: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clip(RoundedCornerShape(14.dp))
            .glass(RoundedCornerShape(14.dp))
            .border(
                1.dp,
                if (required && !granted) MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                RoundedCornerShape(14.dp)
            )
            .clickable(enabled = !granted, onClick = onGrant)
            .padding(14.dp)
    ) {
        Icon(
            if (granted) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
            contentDescription = if (granted) "Granted" else "Not granted",
            tint = when {
                granted -> MaterialTheme.colorScheme.primary
                required -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.outline
            },
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (required && !granted) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.18f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            "REQUIRED",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                why,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
