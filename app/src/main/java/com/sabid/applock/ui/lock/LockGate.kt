package com.sabid.applock.ui.lock

import com.sabid.applock.ui.theme.glass
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.sabid.applock.core.crypto.PinCrypto
import com.sabid.applock.core.data.AppSettings
import com.sabid.applock.core.data.SettingsStore
import com.sabid.applock.core.model.LockMethod
import com.sabid.applock.core.util.AppInfoLoader
import com.sabid.applock.service.LockSession
import com.sabid.applock.ui.theme.AppLockTheme
import com.sabid.applock.ui.theme.Mono
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Phase { CHALLENGE, RECOVER_ANSWER, RECOVER_RESET }

/**
 * The unlock challenge itself. Shared by [LockActivity] and by the in-app gate that
 * protects AppLock's own settings, so there is exactly one implementation of
 * "is this the right person?".
 *
 * Attempt counting and the lockout deadline live in [LockSession], not in composable
 * state. Keeping them here meant an attacker could reset the back-off simply by pressing
 * home and reopening the protected app, since that destroyed and recreated the activity.
 *
 * [activity] is null when this is hosted in a bare WindowManager overlay
 * ([com.sabid.applock.service.LockOverlay]), which is the only presentation Android cannot
 * refuse. BiometricPrompt needs a FragmentActivity, so with no activity the fingerprint
 * button falls back to [onBiometricRequest] — the host's chance to go and find one — and
 * disappears entirely if the host offers no such route.
 */
@Composable
fun LockGate(
    activity: FragmentActivity?,
    targetPackage: String,
    settingsStore: SettingsStore,
    onUnlocked: () -> Unit,
    onGiveUp: () -> Unit,
    onFailedAttempt: (Int) -> Unit = {},
    onBiometricRequest: (() -> Unit)? = null
) {
    val settings by settingsStore.flow.collectAsState(initial = AppSettings())
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var phase by remember { mutableStateOf(Phase.CHALLENGE) }
    var entered by remember { mutableStateOf("") }
    var attempts by remember { mutableStateOf(LockSession.attempts) }
    var error by remember { mutableStateOf(false) }
    var remainingLockout by remember { mutableStateOf((LockSession.lockoutRemainingMs() / 1000).toInt()) }
    var icon by remember { mutableStateOf<ImageBitmap?>(null) }
    var label by remember { mutableStateOf("") }
    // Whether the label lookup has finished, however it turned out.
    //
    // The auto-prompts below wait for this. Compose re-reads the on-screen title when the
    // label arrives, but BiometricPrompt does not: it captures its title once, at
    // authenticate(), so a prompt raised a few milliseconds too early reads "Unlock app" for
    // as long as it is up. Set unconditionally rather than when the label comes back non-blank,
    // so a package whose name cannot be resolved still gets a prompt.
    var labelResolved by remember { mutableStateOf(false) }
    // Covers both auto-prompts below — the biometric shortcut and the device-credential
    // challenge. One flag, because either one having been raised already is the reason not
    // to raise it again on the next recomposition.
    var promptShown by remember { mutableStateOf(false) }
    // Fake "not responding" dialog: shown until the owner long-presses it. Per presentation.
    var crashRevealed by remember { mutableStateOf(false) }
    val showFakeCrash = settings.fakeCrash && !crashRevealed && targetPackage != SELF_GATE

    val pinLength = settings.pinLength.coerceIn(4, 8)

    // Shuffled once per presentation, not per keystroke — re-shuffling mid-entry
    // makes the pad unusable rather than merely private.
    val digits = remember(settings.randomKeyboard) {
        val base = listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 0)
        if (settings.randomKeyboard) base.shuffled() else base
    }

    LaunchedEffect(targetPackage) {
        // Label before icon. Only the label gates the auto-prompt, and the icon is the slower
        // of the two — it decodes a drawable, where this is a string lookup.
        label = AppInfoLoader.label(context, targetPackage)
        labelResolved = true
        icon = AppInfoLoader.icon(context, targetPackage)
    }

    LaunchedEffect(
        settings.biometricEnabled,
        settings.secretHash,
        settings.deviceCredentialUnlock,
        labelResolved,
        showFakeCrash
    ) {
        // Never offer either prompt during a lockout: it would hand an attacker a free
        // channel that bypasses the back-off entirely.
        //
        // Only auto-prompt when we already hold an activity. With no activity the only
        // route is onBiometricRequest, and firing that unprompted would tear the lock
        // screen down and rebuild it every single time a protected app is opened.
        if (activity == null || promptShown || !labelResolved || showFakeCrash || LockSession.isLockedOut) {
            return@LaunchedEffect
        }

        if (settings.deviceCredentialUnlock) {
            // Here the phone's screen lock is not a shortcut past a secret — it is the whole
            // unlock, so there is nothing behind this prompt to fall back to and no reason to
            // make the user tap a button to reach the only door there is.
            promptShown = true
            Biometrics.promptDeviceCredential(
                activity = activity,
                title = "Unlock ${label.ifBlank { "app" }}",
                subtitle = "Use your phone's screen lock",
                allowBiometric = settings.biometricEnabled,
                onSuccess = {
                    LockSession.clearFailures()
                    onUnlocked()
                },
                onFailure = {}
            )
        } else if (settings.biometricEnabled && settings.secret != null) {
            promptShown = true
            Biometrics.prompt(
                activity = activity,
                title = "Unlock ${label.ifBlank { "app" }}",
                subtitle = "Confirm it's you",
                onSuccess = {
                    LockSession.clearFailures()
                    onUnlocked()
                },
                onFallback = {}
            )
        }
    }

    // A single ticker drives the countdown. It polls LockSession rather than a local
    // deadline so the display is right even when the lockout began in a previous
    // instance of this activity.
    LaunchedEffect(Unit) {
        while (true) {
            remainingLockout = ((LockSession.lockoutRemainingMs() + 999) / 1000).toInt()
            delay(250)
        }
    }

    suspend fun submit(secret: CharArray) {
        // Nothing to compare against, or the comparison is not the one in force: refuse
        // rather than open. Neither state should be reachable — LockRepository.decide()
        // declines to challenge at all when no unlock is usable, and the keypad below is not
        // drawn in device-credential mode — so this is a backstop, and a backstop that called
        // onUnlocked() would be an unlock-by-misconfiguration.
        val stored = settings.secret
        if (stored == null || settings.deviceCredentialUnlock) {
            secret.fill('\u0000')
            entered = ""
            return
        }
        if (LockSession.isLockedOut) { secret.fill('\u0000'); entered = ""; return }

        val ok = withContext(Dispatchers.Default) { PinCrypto.verify(secret, stored) }
        secret.fill('\u0000')
        if (ok) {
            LockSession.clearFailures()
            attempts = 0
            onUnlocked()
        } else {
            attempts = LockSession.recordFailure()
            error = true
            entered = ""
            onFailedAttempt(attempts)
        }
    }

    /**
     * Where "unlock with your phone's screen lock" leads, or null if it leads nowhere.
     *
     * BiometricPrompt needs a FragmentActivity. When we have one, ask directly; when we do not
     * — the WindowManager overlay — hand off to [onBiometricRequest], which is the host's
     * existing route for going and finding an activity. Null means neither is available, and
     * the button is then not offered at all rather than offered and dead.
     */
    val requestDeviceCredential: (() -> Unit)? = when {
        activity == null -> onBiometricRequest
        else -> fun() {
            Biometrics.promptDeviceCredential(
                activity = activity,
                title = "Unlock ${label.ifBlank { "app" }}",
                subtitle = "Use your phone's screen lock",
                allowBiometric = settings.biometricEnabled,
                onSuccess = {
                    LockSession.clearFailures()
                    onUnlocked()
                },
                onFailure = {}
            )
        }
    }

    suspend fun submitRecoveryAnswer(answer: String): Boolean {
        val stored = settings.recoveryAnswer ?: return false
        return withContext(Dispatchers.Default) {
            PinCrypto.verify(answer.trim().lowercase().toCharArray(), stored)
        }
    }

    AppLockTheme(theme = settings.theme) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            if (showFakeCrash) {
                FakeCrash(
                    label = label.ifBlank { "App" },
                    onClose = onGiveUp,
                    onReveal = { crashRevealed = true }
                )
                return@Surface
            }
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding()) {
                val wide = maxWidth > maxHeight
                val header: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .glass(RoundedCornerShape(20.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    icon?.let { Image(it, contentDescription = null, modifier = Modifier.size(44.dp)) }
                }

                Spacer(Modifier.height(18.dp))
                Text(
                    text = label.ifBlank { "Protected" },
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(6.dp))
                }
                val body: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {
                when (phase) {
                    Phase.CHALLENGE -> {
                        Text(
                            text = challengeHint(
                                remainingLockout = remainingLockout,
                                error = error,
                                attempts = attempts,
                                method = settings.lockMethod,
                                deviceCredential = settings.deviceCredentialUnlock
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (error || remainingLockout > 0) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )

                        Spacer(Modifier.height(28.dp))

                        // Phone-screen-lock mode draws no keypad, because there is nothing here
                        // to type into one. The `else when` below is left exhaustive over
                        // LockMethod on purpose: a new method must still fail to compile here
                        // rather than fall through to a blank screen.
                        if (settings.deviceCredentialUnlock) {
                            DeviceCredentialChallenge(
                                enabled = remainingLockout == 0,
                                onRequest = requestDeviceCredential
                            )
                        } else when (settings.lockMethod) {
                            LockMethod.PATTERN -> PatternLock(
                                maxSize = if (wide) 230.dp else 320.dp,
                                accent = MaterialTheme.colorScheme.primary,
                                idle = MaterialTheme.colorScheme.outline,
                                invisible = settings.invisiblePattern,
                                error = error,
                                onComplete = { nodes ->
                                    if (remainingLockout > 0) return@PatternLock
                                    error = false
                                    scope.launch { submit(PinCrypto.encodePattern(nodes)) }
                                }
                            )

                            LockMethod.PIN -> {
                                PinDots(count = pinLength, filled = entered.length, error = error)
                                Spacer(Modifier.height(32.dp))
                                PinKeypad(
                                    keyAspect = if (wide) 2.3f else 1.25f,
                                    digits = digits,
                                    showBiometric = settings.biometricEnabled && remainingLockout == 0 &&
                                        (activity != null || onBiometricRequest != null),
                                    vibrate = settings.vibrateOnKey,
                                    onDigit = { d ->
                                        if (remainingLockout > 0) return@PinKeypad
                                        error = false
                                        if (entered.length < pinLength) entered += d
                                        if (entered.length == pinLength) {
                                            val snapshot = entered.toCharArray()
                                            scope.launch { submit(snapshot) }
                                        }
                                    },
                                    onBackspace = { entered = entered.dropLast(1); error = false },
                                    onBiometric = {
                                        if (remainingLockout > 0) return@PinKeypad
                                        if (activity == null) {
                                            onBiometricRequest?.invoke()
                                            return@PinKeypad
                                        }
                                        Biometrics.prompt(
                                            activity, "Unlock ${label.ifBlank { "app" }}",
                                            "Confirm it's you",
                                            {
                                                LockSession.clearFailures()
                                                onUnlocked()
                                            },
                                            {}
                                        )
                                    }
                                )
                            }
                        }

                        Spacer(Modifier.height(24.dp))

                        // Not offered in device-credential mode: recovery resets the AppLock
                        // secret, which is not what is being asked for here, and a "forgot it?"
                        // link that hands out a new AppLock PIN would be a way around the
                        // phone's own screen lock rather than a way through it.
                        AnimatedVisibility(
                            visible = settings.recoveryQuestion != null && attempts >= 2 &&
                                !settings.deviceCredentialUnlock
                        ) {
                            TextButton(onClick = { phase = Phase.RECOVER_ANSWER }) {
                                Text(
                                    "Forgot ${if (settings.lockMethod == LockMethod.PATTERN) "pattern" else "PIN"}?"
                                )
                            }
                        }
                        TextButton(onClick = onGiveUp) { Text("Close") }
                    }

                    Phase.RECOVER_ANSWER -> RecoveryAnswer(
                        question = settings.recoveryQuestion.orEmpty(),
                        onVerify = { candidate, onResult ->
                            scope.launch { onResult(submitRecoveryAnswer(candidate)) }
                        },
                        onVerified = { phase = Phase.RECOVER_RESET },
                        onCancel = { phase = Phase.CHALLENGE }
                    )

                    Phase.RECOVER_RESET -> {
                        Text(
                            "Answer accepted. Set a new unlock.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(20.dp))
                        SecretSetup(
                            vibrate = settings.vibrateOnKey,
                            onCancel = { phase = Phase.CHALLENGE },
                            onDone = { fresh ->
                                scope.launch {
                                    settingsStore.setSecret(fresh.method, fresh.secret, fresh.pinLength)
                                    fresh.secret.fill('\u0000')
                                    LockSession.clearFailures()
                                    onUnlocked()
                                }
                            }
                        )
                    }
                }
                }
                if (wide) {
                    Row(
                        Modifier.fillMaxSize().padding(horizontal = 28.dp),
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            Modifier.weight(1f).fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) { header() }
                        Column(
                            Modifier.weight(1f).fillMaxSize().verticalScroll(rememberScrollState()),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) { body(); Spacer(Modifier.height(12.dp)) }
                    }
                } else {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Spacer(Modifier.height(56.dp))
                        header()
                        body()
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }
}

private fun challengeHint(
    remainingLockout: Int,
    error: Boolean,
    attempts: Int,
    method: LockMethod,
    deviceCredential: Boolean
): String = when {
    remainingLockout > 0 -> "Too many attempts · try again in ${remainingLockout}s"
    error && attempts < LockSession.LOCKOUT_AFTER ->
        LockSession.LOCKOUT_AFTER.minus(attempts).let { left ->
            "That didn't match. $left ${if (left == 1) "try" else "tries"} left"
        }
    error -> "That didn't match"
    // Checked before the method, because in this mode [method] describes a secret that is
    // stored but no longer in force — telling the user to enter a PIN AppLock will not accept
    // is worse than saying nothing.
    deviceCredential -> "Confirm your phone's PIN, pattern or password"
    method == LockMethod.PATTERN -> "Draw your pattern"
    else -> "Enter your PIN"
}

/**
 * The whole challenge when the phone's own screen lock is the unlock.
 *
 * One button, and none of the machinery that hangs off counting wrong entries — no back-off of
 * our own, no intruder photo, no vault-cache wipe. The credential screen belongs to the system,
 * which throttles failures itself and reports only success, so there is nothing here to count.
 * That is part of the trade-off, and it is stated where the choice is made
 * ([UnlockSetupPrompt]) and again next to the settings it disables, rather than discovered here.
 */
@Composable
private fun DeviceCredentialChallenge(enabled: Boolean, onRequest: (() -> Unit)?) {
    if (onRequest == null) {
        Text(
            "Open AppLock to unlock this app.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        return
    }
    Button(
        onClick = onRequest,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text("Unlock with phone screen lock")
    }
}

@Composable
private fun RecoveryAnswer(
    question: String,
    onVerify: (String, (Boolean) -> Unit) -> Unit,
    onVerified: () -> Unit,
    onCancel: () -> Unit
) {
    var answer by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }

    Text(
        question.ifBlank { "Answer your recovery question" },
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(20.dp))
    OutlinedTextField(
        value = answer,
        onValueChange = { answer = it; wrong = false },
        label = { Text("Your answer") },
        isError = wrong,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    )
    if (wrong) {
        Spacer(Modifier.height(8.dp))
        Text(
            "That isn't the answer on file.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error
        )
    }
    Spacer(Modifier.height(16.dp))
    TextButton(
        enabled = answer.isNotBlank() && !checking,
        onClick = {
            checking = true
            onVerify(answer) { ok ->
                checking = false
                if (ok) onVerified() else wrong = true
            }
        }
    ) { Text(if (checking) "Checking…" else "Continue") }
    TextButton(onClick = onCancel) { Text("Back") }
}

@Composable
private fun PinDots(count: Int, filled: Int, error: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        repeat(count) { i ->
            val active = i < filled
            val scale by animateFloatAsState(if (active) 1f else 0.7f, label = "dot")
            Box(
                modifier = Modifier
                    .size((14 * scale).dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            error -> MaterialTheme.colorScheme.error
                            active -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.outline
                        }
                    )
            )
        }
    }
}

@Composable
fun PinLengthHint(length: Int) {
    Text(
        text = "$length digits",
        fontFamily = Mono,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** Gate target used by AppLock's own settings gate; never disguised. */
private const val SELF_GATE = "com.sabid.applock"

/**
 * A convincing "isn't responding" dialog. Close dismisses the lock like a normal give-up;
 * Wait does nothing; a long press on the message reveals the real lock screen.
 */
@Composable
private fun FakeCrash(label: String, onClose: () -> Unit, onReveal: () -> Unit) {
    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xB3000000)), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .padding(horizontal = 36.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(androidx.compose.ui.graphics.Color(0xFF2B2B2F))
                .padding(24.dp)
        ) {
            Text(
                "$label isn't responding",
                style = MaterialTheme.typography.titleLarge,
                color = androidx.compose.ui.graphics.Color(0xFFE6E1E5),
                modifier = Modifier.pointerInput(Unit) {
                    detectTapGestures(onLongPress = { onReveal() })
                }
            )
            Spacer(Modifier.height(24.dp))
            Column {
                TextButton(onClick = onClose) { Text("Close app") }
                TextButton(onClick = {}) { Text("Wait") }
            }
        }
    }
}
