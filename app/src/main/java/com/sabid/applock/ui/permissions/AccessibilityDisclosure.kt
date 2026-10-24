package com.sabid.applock.ui.permissions

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Accessibility
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.sabid.applock.core.util.Permissions
import com.sabid.applock.core.util.SpecialGrant

/**
 * The prominent disclosure that must be shown, and accepted, before AppLock asks for the
 * AccessibilityService grant.
 *
 * Play rejected the app twice over this ("Prominent disclosure Non compliant design"), so
 * every rule in the User Data policy is answered explicitly here rather than approximately.
 * The policy asks that the disclosure:
 *
 *  - live inside the app, not only in the listing or a privacy policy. It is on screen here.
 *  - be "displayed in the normal usage of the app and not require the user to navigate into a
 *    menu or settings". [AccessibilityDisclosureScreen] is a step of first run, reached
 *    without tapping anything optional, which is why the disclosure is not only a dialog.
 *  - describe the data accessed and how it is used. [DisclosureBody] does both, in the terms
 *    a user would recognise rather than in the API's terms.
 *  - not be bundled with unrelated disclosures. Nothing about overlays, notifications, the
 *    camera or battery appears here; those live on their own screen.
 *  - be immediately followed by the request, never long before it. Both entry points open the
 *    Accessibility settings page as the direct result of the accept button.
 *  - take affirmative action, offer two clearly-labelled options, never read navigation away
 *    as consent, and never auto-dismiss. Hence two buttons, and a dialog that switches off
 *    back-press and outside-tap entirely so the only exits are the buttons themselves.
 *
 * Play states plainly that a single "Continue"/"OK" button, or a toggle, is not consent, so
 * the two buttons are equally weighted and decline comes first.
 */
private const val DISCLOSURE_TITLE = "AppLock uses the Accessibility API"

/** The accept and decline labels, in one place so both surfaces agree. */
private const val AGREE = "I agree"
private const val DECLINE = "No thanks"

/**
 * The disclosure text itself, shared by the dialog and the first-run screen so the two can
 * never drift into saying different things about the same permission.
 *
 * The wording is specific about what is *not* read, and that claim is enforced rather than
 * promised: the service declares `canRetrieveWindowContent="false"` in
 * `res/xml/accessibility_service_config.xml`, so Android itself withholds screen contents.
 */
@Composable
private fun DisclosureBody() {
    Body(
        "To put a lock screen in front of the apps you choose, AppLock has to know which app " +
            "you have just opened. Android only reports that through the Accessibility API."
    )
    Spacer(Modifier.height(12.dp))
    Point(
        "What it reads",
        "The package name of the app that comes to the foreground — for example " +
            "\"com.whatsapp\" — and nothing else."
    )
    Point(
        "What it is used for",
        "Deciding whether that app is on your locked list, and showing the lock screen if " +
            "it is. That happens entirely on this phone."
    )
    Point(
        "What it cannot do",
        "It cannot read the contents of your screen, your messages, or anything you type. " +
            "The service is configured without window-content access, so this is enforced " +
            "by Android rather than left to a promise."
    )
    Point(
        "Where it goes",
        "Nowhere. Nothing is recorded, shared, sold, or sent off your phone. AppLock has no " +
            "account system and no server to send it to."
    )
    Spacer(Modifier.height(12.dp))
    Body(
        "If you agree, Android's Accessibility settings will open so you can turn on " +
            "\"AppLock protection\" yourself. You can turn it off again at any time in the " +
            "same place, and AppLock keeps working without it — it just locks a little slower."
    )
}

/**
 * First-run presentation: a step of onboarding given over to this one disclosure.
 *
 * A dialog alone would satisfy "before the request" but leaves the policy's other clause —
 * shown in normal usage, not behind a menu — resting on the user having tapped a permission
 * row. A step of the onboarding flow does not rest on anything.
 *
 * Deliberately content-only: no Surface, no fillMaxSize, no scroll container of its own. The
 * onboarding flow already supplies all three, and a second vertical scroller nested in the
 * first is measured against an infinite height and throws.
 *
 * [onAgree] receives whether a Settings page actually opened, since some ROMs have no
 * reachable Accessibility page and the caller says so rather than appearing to do nothing.
 */
@Composable
fun AccessibilityDisclosureScreen(onAgree: (opened: Boolean) -> Unit, onDecline: () -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.Accessibility,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(30.dp)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                DISCLOSURE_TITLE,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
        Spacer(Modifier.height(20.dp))
        DisclosureBody()
        Spacer(Modifier.height(28.dp))
        Choice(
            onDecline = onDecline,
            onAgree = { onAgree(openAccessibilitySettings(context)) }
        )
        Spacer(Modifier.height(40.dp))
    }
}

/**
 * The same disclosure as a dialog, for every later request: the Settings screen, the
 * protection banner and the permission gate that appears when a grant has been revoked.
 *
 * Back-press and outside-tap are switched off rather than routed to decline. Either would be
 * compliant on its own reading — the policy forbids treating them as *consent* — but a dialog
 * that cannot be dismissed at all removes the question, and there is no trap in it because
 * declining is one tap away.
 */
@Composable
private fun AccessibilityDisclosureDialog(onAgree: () -> Unit, onDecline: () -> Unit) {
    Dialog(
        onDismissRequest = onDecline,
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        )
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 2.dp
        ) {
            Column(Modifier.padding(22.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.Accessibility,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(26.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        DISCLOSURE_TITLE,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(Modifier.height(14.dp))
                // Capped and scrollable so both buttons stay on screen on a short display. A
                // disclosure whose decline button has scrolled out of view is the same failure
                // as not offering one.
                Column(
                    Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    DisclosureBody()
                }
                Spacer(Modifier.height(20.dp))
                Choice(onDecline = onDecline, onAgree = onAgree)
            }
        }
    }
}

/**
 * The two buttons, identical on both surfaces.
 *
 * Decline is placed first and given equal width, so accepting is never simply the one the
 * thumb lands on. Neither is styled to look like the only way forward.
 */
@Composable
private fun ColumnScope.Choice(onDecline: () -> Unit, onAgree: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(
            onClick = onDecline,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.weight(1f).height(50.dp)
        ) { Text(DECLINE) }
        Button(
            onClick = onAgree,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.weight(1f).height(50.dp)
        ) { Text(AGREE) }
    }
}

@Composable
private fun Body(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
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

/**
 * The only place in the app that opens the Accessibility settings page.
 *
 * Private on purpose, and `check.py` fails the build if any file outside this one names
 * [SpecialGrant.ACCESSIBILITY] in an `open()` call — the undisclosed shortcut is exactly the
 * regression that got the app rejected, and it would look perfectly correct in review.
 */
private fun openAccessibilitySettings(context: Context): Boolean =
    Permissions.open(context, SpecialGrant.ACCESSIBILITY)

/**
 * The supported way to ask for the accessibility grant after first run.
 *
 * Emits the disclosure dialog when needed and returns the lambda that raises it, so a call
 * site reads `ask()` instead of reaching for the Settings page itself. [onResult] reports
 * whether a page actually opened; it is not called when the user declines, because nothing
 * was attempted and there is no failure to report.
 */
@Composable
fun rememberAccessibilityDisclosure(onResult: (opened: Boolean) -> Unit = {}): () -> Unit {
    val context = LocalContext.current
    var asking by remember { mutableStateOf(false) }

    if (asking) {
        AccessibilityDisclosureDialog(
            onAgree = {
                asking = false
                onResult(openAccessibilitySettings(context))
            },
            onDecline = { asking = false }
        )
    }

    return { asking = true }
}
