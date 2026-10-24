package com.sabid.applock.ui.lock

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
import androidx.compose.material.icons.outlined.Dialpad
import androidx.compose.material.icons.outlined.PhonelinkLock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.fragment.app.FragmentActivity
import com.sabid.applock.core.util.Unlock

/**
 * The question asked the first time the user locks an app: what should AppLock check?
 *
 * Deliberately not asked during onboarding. Being made to invent a PIN before you have said
 * you want anything locked is a toll gate on an app you have not decided to use yet, and the
 * answer it collects is worth less than the one collected here — at this point the user has a
 * specific app in mind and knows exactly what the PIN is for.
 *
 * Three answers, and "not now" is a real one. Declining still locks the app; it just leaves
 * AppLock with nothing to check, which the banner on the Apps screen then says plainly and
 * offers to fix. A prompt that cannot be declined is a prompt users learn to defeat rather
 * than answer, and the honest failure mode — locked list, no enforcement, said out loud — is
 * better than a wall.
 *
 * The phone's-screen-lock option exists because the alternative for someone who does not want
 * a second secret was no lock at all. Its cost is stated on the card rather than buried: it
 * makes "can unlock this phone" and "can open the locked apps" the same permission. Choosing
 * it requires passing the credential prompt once, which is both consent and the only way to
 * discover that the prompt works on this ROM before it is the only door there is.
 */
@Composable
fun UnlockSetupPrompt(
    activity: FragmentActivity?,
    lockedLabel: String?,
    onChooseSecret: () -> Unit,
    onUseDeviceCredential: () -> Unit,
    onDecline: () -> Unit
) {
    val context = LocalContext.current
    val phoneLockAvailable = remember { Unlock.deviceCredentialSet(context) }
    var credentialFailed by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDecline) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 2.dp
        ) {
            Column(Modifier.padding(22.dp)) {
                Text(
                    "How should AppLock check it's you?",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (lockedLabel != null) {
                        "$lockedLabel is on your locked list now, but AppLock has nothing to " +
                            "ask for yet. Pick one — you'll only be asked this once."
                    } else {
                        "AppLock has nothing to ask for yet, so nothing on your list is " +
                            "actually being held shut. Pick one — you'll only be asked once."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(18.dp))

                Option(
                    icon = Icons.Outlined.Dialpad,
                    title = "Set an AppLock PIN or pattern",
                    detail = "A separate secret, just for locked apps. Someone who can " +
                        "unlock your phone still can't open them.",
                    onClick = onChooseSecret
                )

                Spacer(Modifier.height(10.dp))

                Option(
                    icon = Icons.Outlined.PhonelinkLock,
                    title = "Use my phone's screen lock",
                    detail = if (phoneLockAvailable) {
                        // Both costs, on the card, before the choice. The second one is easy to
                        // leave out and would be found out later: intruder photos are counted
                        // from wrong entries AppLock sees, and it never sees one in this mode.
                        "Nothing new to remember. The trade-offs: anyone who can unlock your " +
                            "phone can then open your locked apps, and intruder photos stop " +
                            "working."
                    } else {
                        "Unavailable — your phone has no PIN, pattern or password set."
                    },
                    enabled = phoneLockAvailable && activity != null,
                    onClick = {
                        // Non-null whenever the card is clickable — see `enabled` above.
                        val host = activity
                        if (host != null) {
                            credentialFailed = false
                            Biometrics.promptDeviceCredential(
                                activity = host,
                                title = "Use your phone's screen lock",
                                subtitle = "Confirm it once to switch this on",
                                // Not the fingerprint shortcut. What has to be proven here
                                // is that the credential prompt itself works and that the
                                // user can pass it, since that is what will stand between
                                // them and their apps from now on.
                                allowBiometric = false,
                                onSuccess = onUseDeviceCredential,
                                onFailure = { credentialFailed = true }
                            )
                        }
                    }
                )

                if (credentialFailed) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "That wasn't confirmed, so nothing was changed. Try again, or set an " +
                            "AppLock PIN instead.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Spacer(Modifier.height(6.dp))
                TextButton(onClick = onDecline, modifier = Modifier.fillMaxWidth()) {
                    Text("Not now")
                }
            }
        }
    }
}

@Composable
private fun Option(
    icon: ImageVector,
    title: String,
    detail: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val accent = if (enabled) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(accent.copy(alpha = if (enabled) 0.10f else 0.04f))
            .border(1.dp, accent.copy(alpha = if (enabled) 0.4f else 0.15f), RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else accent
            )
            Spacer(Modifier.height(3.dp))
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
