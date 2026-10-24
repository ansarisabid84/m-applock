package com.sabid.applock.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.sabid.applock.AppLockApp
import com.sabid.applock.core.data.AppSettings
import com.sabid.applock.core.util.Unlock
import com.sabid.applock.ui.lock.LockActivity
import com.sabid.applock.ui.lock.LockGate
import com.sabid.applock.ui.lock.SecretSetup
import kotlinx.coroutines.launch

private enum class Stage { REAUTH, SECRET, RECOVERY, DONE }

/**
 * Changes the unlock secret and the recovery question.
 *
 * Re-authenticating first is the point of the screen: without it, anyone holding an
 * already-unlocked phone could silently replace the PIN and lock the owner out of their
 * own vault.
 */
@Composable
fun ChangeSecretScreen(activity: FragmentActivity, onDone: () -> Unit) {
    val store = AppLockApp.get().settings
    val settings by store.flow.collectAsState(initial = AppSettings())
    val scope = rememberCoroutineScope()

    // A user who has not set an unlock yet has nothing to re-authenticate against.
    //
    // Unlock.usable rather than a bare `secretHash == null`, for both of the reasons that
    // predicate exists: a phone-screen-lock user has no AppLock secret either, and skipping
    // re-authentication for them would let anyone holding an unlocked phone replace the unlock
    // outright; while a phone-screen-lock user whose phone lock has since been removed has no
    // answerable challenge, and demanding one here would deny them the very screen that fixes it.
    var stage by remember { mutableStateOf(Stage.REAUTH) }
    LaunchedEffect(settings.secretHash, settings.deviceCredentialUnlock) {
        if (!Unlock.usable(activity, settings) && stage == Stage.REAUTH) stage = Stage.SECRET
    }

    when (stage) {
        Stage.REAUTH -> LockGate(
            activity = activity,
            targetPackage = LockActivity.SELF_TARGET,
            settingsStore = store,
            onUnlocked = { stage = Stage.SECRET },
            onGiveUp = onDone
        )

        Stage.SECRET -> Page(
            title = "New unlock",
            subtitle = "Pick a PIN or pattern. You'll enter it twice."
        ) {
            SecretSetup(
                initialMethod = settings.lockMethod,
                vibrate = settings.vibrateOnKey,
                onCancel = onDone,
                onDone = { fresh ->
                    scope.launch {
                        store.setSecret(fresh.method, fresh.secret, fresh.pinLength)
                        fresh.secret.fill(' ')
                        stage = Stage.RECOVERY
                    }
                }
            )
        }

        Stage.RECOVERY -> RecoveryEditor(
            existingQuestion = settings.recoveryQuestion,
            onSave = { q, a ->
                scope.launch {
                    store.setRecovery(q, a.trim().lowercase().toCharArray())
                    stage = Stage.DONE
                }
            },
            onSkip = { stage = Stage.DONE }
        )

        Stage.DONE -> LaunchedEffect(Unit) { onDone() }
    }
}

@Composable
private fun RecoveryEditor(
    existingQuestion: String?,
    onSave: (String, String) -> Unit,
    onSkip: () -> Unit
) {
    var question by remember {
        mutableStateOf(existingQuestion ?: "What was the name of your first school?")
    }
    var answer by remember { mutableStateOf("") }

    Page(
        title = "If you forget it",
        subtitle = "One question only you can answer. Stored as a hash, same as your PIN."
    ) {
        OutlinedTextField(
            value = question,
            onValueChange = { question = it },
            label = { Text("Question") },
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = answer,
            onValueChange = { answer = it },
            label = { Text("Answer") },
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { onSave(question.trim(), answer) },
            enabled = question.isNotBlank() && answer.isNotBlank(),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) { Text("Save", style = MaterialTheme.typography.labelLarge) }
        TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
            Text(if (existingQuestion == null) "Skip for now" else "Keep the current question")
        }
    }
}

@Composable
private fun Page(title: String, subtitle: String, content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(40.dp))
            Text(
                title,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
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
