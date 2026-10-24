package com.sabid.applock.ui.lock

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import com.sabid.applock.ui.theme.glass
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backspace
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sabid.applock.ui.theme.Mono

/**
 * Numeric keypad. [digits] arrives pre-ordered by the caller — pass a shuffled list and
 * an onlooker cannot infer the PIN from finger positions alone.
 */
@Composable
fun PinKeypad(
    digits: List<Int>,
    showBiometric: Boolean,
    onDigit: (Int) -> Unit,
    onBackspace: () -> Unit,
    onBiometric: () -> Unit,
    vibrate: Boolean = false,
    keyAspect: Float = 1.25f,
    modifier: Modifier = Modifier
) {
    val rows = digits.take(9).chunked(3)
    val last = digits.getOrNull(9) ?: 0
    val haptics = LocalHapticFeedback.current

    // Feedback fires here rather than in every caller, so the `vibrateOnKey` setting
    // covers backspace and the biometric key too and cannot be half-wired.
    val tap: (() -> Unit) -> Unit = { action ->
        if (vibrate) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        action()
    }

    Column(modifier = modifier.widthIn(max = 340.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                row.forEach { d -> Key(d.toString(), keyAspect, Modifier.weight(1f)) { tap { onDigit(d) } } }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f).aspectRatio(keyAspect), contentAlignment = Alignment.Center) {
                if (showBiometric) {
                    Icon(
                        Icons.Outlined.Fingerprint,
                        contentDescription = "Unlock with fingerprint",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(30.dp).clip(CircleShape).clickable { tap(onBiometric) }
                    )
                }
            }
            Key(last.toString(), keyAspect, Modifier.weight(1f)) { tap { onDigit(last) } }
            Box(Modifier.weight(1f).aspectRatio(keyAspect), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Outlined.Backspace,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp).clip(CircleShape).clickable { tap(onBackspace) }
                )
            }
        }
    }
}

private val KeyShape = androidx.compose.foundation.shape.RoundedCornerShape(26.dp)

@Composable
private fun Key(label: String, aspect: Float, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .aspectRatio(aspect)
            .clip(KeyShape)
            .glass(KeyShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 26.sp,
            fontWeight = FontWeight.Light,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
