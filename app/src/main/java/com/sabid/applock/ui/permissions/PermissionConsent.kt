package com.sabid.applock.ui.permissions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Every permission that Android treats as dangerous or special, other than Accessibility (which
 * has its own wording in [rememberAccessibilityDisclosure]).
 *
 * Each one is explained in the same shape — what it reads or does, what it is used for, what it
 * cannot do, where the data goes — and the system prompt or Settings page only opens after the
 * user taps "I agree". "No thanks" changes nothing.
 */
enum class SensitiveGrant(
    val title: String,
    val icon: ImageVector,
    val intro: String,
    val reads: String,
    val usedFor: String,
    val cannot: String,
    val goes: String,
    val after: String
) {
    OVERLAY(
        "Display over other apps",
        Icons.Outlined.Layers,
        "To ask for your PIN in front of a protected app, AppLock has to draw its lock screen on top of other apps.",
        "Nothing. This permission only lets AppLock show a window; it does not read what is under it.",
        "Drawing the lock screen when you open an app on your locked list.",
        "It cannot see, record or change what other apps display, and it cannot tap or type for you.",
        "Nowhere. Nothing about this is stored or sent off your phone.",
        "If you agree, Android's \"Display over other apps\" page opens so you can switch AppLock on. Without it AppLock can only send you back to the home screen."
    ),
    USAGE_ACCESS(
        "Usage access",
        Icons.Outlined.QueryStats,
        "Some phones stop background services without warning. Usage access gives AppLock a second way to notice which app is open.",
        "Which app is currently in front, and when apps were opened or closed. Not what is inside them.",
        "A backup detector when the accessibility service has been stopped, and checking that a locked app has really left the screen.",
        "It cannot read your messages, screen contents or anything you type.",
        "Nowhere. The information is used in the moment and is not saved or sent off your phone.",
        "If you agree, Android's \"Usage access\" page opens so you can allow AppLock. You can switch it off there at any time."
    ),
    CAMERA(
        "Camera",
        Icons.Outlined.CameraAlt,
        "AppLock can take a photo of whoever enters the wrong PIN or pattern several times in a row.",
        "One photo from the front camera, only after repeated wrong attempts. Nothing else ever uses the camera.",
        "Showing you who tried to get into a locked app, in the Intruders tab.",
        "It cannot record video or take photos in the background at any other time.",
        "Photos stay on this phone. They are deleted automatically after the number of days you choose, and you can delete them yourself at any time. Nothing is uploaded.",
        "If you agree, Android will ask you to allow the camera. You can turn intruder photos off in Settings whenever you like."
    ),
    NOTIFICATIONS(
        "Notifications",
        Icons.Outlined.Notifications,
        "AppLock shows a small status badge and a few alerts.",
        "Nothing. This only lets AppLock post notifications.",
        "A badge that shows protection is running, an alert when an intruder photo is taken, and a warning if protection stops working.",
        "It will not send promotions, ads or reminders to open the app.",
        "Notifications are shown on this phone only. Nothing is sent anywhere.",
        "If you agree, Android will ask you to allow notifications."
    ),
    BATTERY(
        "Ignore battery optimisation",
        Icons.Outlined.BatteryChargingFull,
        "To keep locking reliably, AppLock asks Android not to put it to sleep in the background.",
        "Nothing. This is a setting about how Android schedules AppLock, not access to your data.",
        "Stopping Android from silently suspending protection, which would leave apps unlocked.",
        "It does not give AppLock any extra access. It can use slightly more battery.",
        "Nowhere. Nothing is collected.",
        "If you agree, Android will ask you to let AppLock run without battery restrictions. You can undo it in Settings."
    ),
    DEVICE_ADMIN(
        "Uninstall protection",
        Icons.Outlined.AdminPanelSettings,
        "Making AppLock a device administrator stops it from being uninstalled until the admin role is switched off.",
        "Nothing. AppLock asks for no administrator powers beyond blocking uninstall.",
        "Preventing someone from removing AppLock, and every lock with it, without going through your PIN.",
        "It cannot erase your phone, change your screen lock, lock the screen, or restrict the camera.",
        "Nowhere. Nothing is collected.",
        "If you agree, Android shows its \"Activate device admin\" screen. You can switch this off again in AppLock's settings."
    ),
    AUTOSTART(
        "Autostart / background restrictions",
        Icons.Outlined.RestartAlt,
        "Some phone makers block apps from starting in the background on their own settings page.",
        "Nothing. This is a setting about when AppLock may run.",
        "Letting AppLock start after a reboot and keep running so locks keep working.",
        "It does not give AppLock access to your data.",
        "Nowhere. Nothing is collected.",
        "If you agree, your phone's own settings page opens so you can allow AppLock to run in the background."
    )
}

/**
 * Returns a function that asks for consent for a [SensitiveGrant] and, only if the user agrees,
 * runs [action] — the call that opens the system prompt or Settings page.
 *
 * The dialog cannot be dismissed by tapping outside or pressing back, so the only exits are the
 * two equal-weight buttons, and "No thanks" is first. Same rules as the Accessibility
 * disclosure, applied to every other sensitive permission.
 */
@Composable
fun rememberConsent(): (SensitiveGrant, () -> Unit) -> Unit {
    var pending by remember { mutableStateOf<Pair<SensitiveGrant, () -> Unit>?>(null) }

    pending?.let { (grant, action) ->
        ConsentDialog(
            grant = grant,
            onAgree = {
                pending = null
                action()
            },
            onDecline = { pending = null }
        )
    }

    return remember { { grant, action -> pending = grant to action } }
}

@Composable
private fun ConsentDialog(grant: SensitiveGrant, onAgree: () -> Unit, onDecline: () -> Unit) {
    Dialog(
        onDismissRequest = onDecline,
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 2.dp
        ) {
            Column(Modifier.padding(22.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        grant.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(26.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        grant.title,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(Modifier.height(14.dp))
                // Scrollable and capped so both buttons stay on screen on a short display.
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        grant.intro,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Point("What it reads", grant.reads)
                    Point("What it is used for", grant.usedFor)
                    Point("What it cannot do", grant.cannot)
                    Point("Where it goes", grant.goes)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        grant.after,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = onDecline,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f).height(50.dp)
                    ) { Text("No thanks") }
                    Button(
                        onClick = onAgree,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f).height(50.dp)
                    ) { Text("I agree") }
                }
            }
        }
    }
}

@Composable
private fun Point(heading: String, detail: String) {
    Column(Modifier.padding(bottom = 10.dp)) {
        Text(
            heading,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(2.dp))
        Text(
            detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
