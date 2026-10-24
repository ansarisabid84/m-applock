package com.sabid.applock.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.sabid.applock.AppLockApp
import com.sabid.applock.core.data.LockProfile
import com.sabid.applock.service.LockSession
import kotlinx.coroutines.launch

/**
 * Lock profiles: saved sets of protected apps. Tap to switch the locked set to that profile,
 * long-press to delete, "Save current" stores what is locked right now.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProfileBar(locked: Set<String>) {
    val scope = rememberCoroutineScope()
    val repo = AppLockApp.get().repo
    val profiles by repo.profiles.collectAsState(initial = emptyList())
    var naming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<LockProfile?>(null) }
    var name by remember { mutableStateOf("") }

    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        profiles.forEach { p ->
            val active = p.packages == locked
            Text(
                p.name,
                style = MaterialTheme.typography.labelLarge,
                color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
                    .combinedClickable(
                        onClick = {
                            scope.launch {
                                repo.applyProfile(p)
                                p.packages.forEach { LockSession.revoke(it) }
                            }
                        },
                        onLongClick = { deleting = p }
                    )
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }
        Row(
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                .combinedClickable(onClick = { name = ""; naming = true })
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Add, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(6.dp))
            Text("Save as profile", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }

    if (naming) {
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text("Save ${locked.size} locked apps") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(20) },
                    placeholder = { Text("Work, Home, Kids…") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = { scope.launch { repo.saveProfile(name, locked) }; naming = false }
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { naming = false }) { Text("Cancel") } }
        )
    }
    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete \"${p.name}\"?") },
            text = { Text("Your locked apps stay as they are.") },
            confirmButton = {
                TextButton(onClick = { scope.launch { repo.deleteProfile(p) }; deleting = null }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }
        )
    }
}
