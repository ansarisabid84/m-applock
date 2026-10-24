package com.sabid.applock.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sabid.applock.core.data.AppSettings
import com.sabid.applock.core.util.Permissions
import com.sabid.applock.core.util.SpecialGrant
import com.sabid.applock.ui.permissions.rememberAccessibilityDisclosure
import com.sabid.applock.core.util.ProtectionStatus
import kotlinx.coroutines.delay

/**
 * Re-reads the whole protection chain on resume, and then keeps re-reading while on screen.
 *
 * Resume alone was not enough, and the gap was user-visible. The accessibility service is
 * bound asynchronously, so on a cold start the first read routinely lands before the bind
 * and says "your phone killed the service"; with a resume-only trigger that verdict then sat
 * there for the entire visit, no matter how quickly the service actually arrived. Polling
 * while composed means the banner corrects itself a second later instead of sending the user
 * off to fix a problem that no longer exists — and equally, a service killed *while* the
 * user is looking at this screen now shows up without waiting for them to leave and return.
 */
@Composable
fun rememberProtectionStatus(settings: AppSettings, lockedCount: Int): ProtectionStatus {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var tick by remember { mutableStateOf(0) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) tick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Only runs while this screen is composed and the process is foreground, so the cost is
    // a handful of cheap local reads a second, none of them IPC-heavy.
    LaunchedEffect(Unit) {
        while (true) {
            delay(RECHECK_INTERVAL_MS)
            tick++
        }
    }

    return remember(settings, lockedCount, tick) {
        ProtectionStatus.read(context, settings, lockedCount)
    }
}

private const val RECHECK_INTERVAL_MS = 1_000L

/**
 * The one surface that is not allowed to lie.
 *
 * Both permission gates can be skipped — deliberately, so a ROM that refuses to open its
 * own Settings pages cannot trap the user out of their vault. The cost was that skipping
 * them dropped you onto a list of apps with switches that appeared to work and didn't. This
 * banner sits above that list and cannot be dismissed, so "1 locked" is never the only
 * thing the screen says.
 *
 * [onSetUpUnlock] is the one fix that lives inside AppLock rather than in system Settings, so
 * it gets a real button here instead of the whole-card "Tap to fix" treatment. That is the
 * requirement rather than a flourish: someone who declined the setup prompt needs the offer to
 * stay in front of them and to be actionable from where the warning is, not to be told to go
 * and find a screen.
 */
@Composable
fun ProtectionBanner(status: ProtectionStatus, onSetUpUnlock: (() -> Unit)? = null) {
    val problem = status.problem ?: return
    val context = LocalContext.current
    var openFailed by remember { mutableStateOf(false) }
    // The accessibility grant is the one fix that cannot be opened straight from here: Play
    // requires its disclosure first, and this card is a route to that Settings page like any
    // other. Every other SpecialGrant goes direct.
    val askAccessibility = rememberAccessibilityDisclosure { opened -> openFailed = !opened }
    val consent = com.sabid.applock.ui.permissions.rememberConsent()

    // Red for "nothing is locking", amber for "locking works but is fragile".
    val severe = status.broken
    val accent = if (severe) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(accent.copy(alpha = 0.12f))
            .border(1.dp, accent.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
            .clickable(enabled = problem.fix != null) {
                when (val fix = problem.fix) {
                    null -> Unit
                    SpecialGrant.ACCESSIBILITY -> askAccessibility()
                    else -> {
                        val kind = when (fix) {
                            SpecialGrant.OVERLAY -> com.sabid.applock.ui.permissions.SensitiveGrant.OVERLAY
                            SpecialGrant.USAGE_ACCESS -> com.sabid.applock.ui.permissions.SensitiveGrant.USAGE_ACCESS
                            SpecialGrant.BATTERY -> com.sabid.applock.ui.permissions.SensitiveGrant.BATTERY
                            else -> com.sabid.applock.ui.permissions.SensitiveGrant.AUTOSTART
                        }
                        consent(kind) { openFailed = !Permissions.open(context, fix) }
                    }
                }
            }
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (severe) Icons.Outlined.ErrorOutline else Icons.Outlined.WarningAmber,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                problem.title,
                style = MaterialTheme.typography.titleMedium,
                color = accent
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            problem.detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (problem.fix != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                if (openFailed) "Could not open that page — use Settings > Apps > AppLock"
                else "Tap to fix",
                style = MaterialTheme.typography.labelLarge,
                color = accent
            )
        }
        if (problem.setUpUnlock && onSetUpUnlock != null) {
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onSetUpUnlock,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = accent,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) { Text("Set up now", style = MaterialTheme.typography.labelLarge) }
        }
    }
}
