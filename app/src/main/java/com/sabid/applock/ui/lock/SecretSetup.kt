package com.sabid.applock.ui.lock

import com.sabid.applock.ui.theme.glass
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sabid.applock.core.model.LockMethod

/** What the caller gets back once the user has entered the same secret twice. */
data class NewSecret(val method: LockMethod, val secret: CharArray, val pinLength: Int) {
    // Data class equality on a CharArray compares references, which is never what anyone
    // wants and is a classic source of "why didn't my state update" bugs. Compare content.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NewSecret) return false
        return method == other.method && secret.contentEquals(other.secret) && pinLength == other.pinLength
    }

    override fun hashCode(): Int = 31 * (31 * method.hashCode() + secret.contentHashCode()) + pinLength
}

/**
 * Enter-then-confirm flow for a PIN or pattern.
 *
 * Shared by onboarding, "change PIN" in settings, and the recovery flow so there is one
 * implementation of the confirm-and-mismatch rules. The caller persists the result; this
 * composable deliberately knows nothing about DataStore.
 */
@Composable
fun SecretSetup(
    onDone: (NewSecret) -> Unit,
    allowMethodChoice: Boolean = true,
    initialMethod: LockMethod = LockMethod.PIN,
    vibrate: Boolean = true,
    onCancel: (() -> Unit)? = null
) {
    var method by remember { mutableStateOf(initialMethod) }
    var length by remember { mutableStateOf(4) }
    var first by remember { mutableStateOf("") }
    var entry by remember { mutableStateOf("") }
    var confirming by remember { mutableStateOf(false) }
    var mismatch by remember { mutableStateOf(false) }

    fun reset() {
        first = ""; entry = ""; confirming = false
    }

    fun accept(encoded: String) {
        if (!confirming) {
            first = encoded; entry = ""; mismatch = false; confirming = true
            return
        }
        if (encoded == first) {
            val out = NewSecret(method, first.toCharArray(), length)
            reset()
            onDone(out)
        } else {
            mismatch = true
            reset()
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(
            when {
                confirming -> "Enter it again to confirm"
                method == LockMethod.PATTERN -> "Draw a pattern through at least 4 dots"
                else -> "Choose a $length-digit PIN"
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        if (mismatch) {
            Spacer(Modifier.height(10.dp))
            Text(
                "Those didn't match. Start again.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }

        Spacer(Modifier.height(20.dp))

        if (allowMethodChoice && !confirming) {
            Chips(
                options = listOf("PIN" to LockMethod.PIN, "Pattern" to LockMethod.PATTERN),
                selected = method,
                onSelect = { method = it; mismatch = false; reset() }
            )
            Spacer(Modifier.height(12.dp))
        }

        if (method == LockMethod.PIN && !confirming) {
            Chips(
                options = listOf("4 digits" to 4, "6 digits" to 6, "8 digits" to 8),
                selected = length,
                onSelect = { length = it; mismatch = false; reset() }
            )
            Spacer(Modifier.height(24.dp))
        } else {
            Spacer(Modifier.height(12.dp))
        }

        when (method) {
            LockMethod.PATTERN -> PatternLock(
                accent = MaterialTheme.colorScheme.primary,
                idle = MaterialTheme.colorScheme.outline,
                error = mismatch,
                // PatternLock only emits patterns of MIN_NODES or more, so there is no
                // length check to repeat here.
                onComplete = { nodes -> accept(nodes.joinToString("-")) }
            )

            LockMethod.PIN -> {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    repeat(length) { i ->
                        Box(
                            Modifier
                                .size(14.dp)
                                .clip(CircleShape)
                                .background(
                                    if (i < entry.length) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outline
                                )
                        )
                    }
                }
                Spacer(Modifier.height(28.dp))
                PinKeypad(
                    digits = listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 0),
                    showBiometric = false,
                    vibrate = vibrate,
                    onDigit = { d ->
                        mismatch = false
                        if (entry.length < length) entry += d
                        // Read the length off the local before accept() resets it.
                        if (entry.length == length) accept(entry)
                    },
                    onBackspace = { entry = entry.dropLast(1) },
                    onBiometric = {}
                )
            }
        }

        if (onCancel != null) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { reset(); onCancel() }) { Text("Cancel") }
        }
    }
}

@Composable
private fun <T> Chips(options: List<Pair<String, T>>, selected: T, onSelect: (T) -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .glass(RoundedCornerShape(12.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        options.forEach { (label, value) ->
            val active = value == selected
            Box(
                Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (active) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .clickable { onSelect(value) }
                    .padding(horizontal = 18.dp, vertical = 9.dp)
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (active) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
