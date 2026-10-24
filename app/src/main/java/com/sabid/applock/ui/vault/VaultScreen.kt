package com.sabid.applock.ui.vault

import com.sabid.applock.ui.theme.glass
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sabid.applock.AppLockApp
import com.sabid.applock.core.data.VaultItem
import com.sabid.applock.core.model.VaultKind
import com.sabid.applock.core.util.VaultManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VaultScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = AppLockApp.get().repo
    val items by repo.vaultItems.collectAsState(initial = emptyList())
    var importing by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<VaultItem?>(null) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            uris.forEach { uri ->
                VaultManager.import(context, uri)?.let { repo.addVaultItem(it) }
            }
            importing = false
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { picker.launch(arrayOf("image/*", "video/*", "audio/*", "application/*")) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text("Add")
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).statusBarsPadding()) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                Text("Vault", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
                Text(
                    "${items.size} items · AES-256-GCM, key held in secure hardware",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (importing) LinearProgressIndicator(Modifier.fillMaxWidth())

            if (items.isEmpty() && !importing) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Nothing hidden yet.\nAnything you add here is encrypted and\nremoved from your gallery.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(108.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(items, key = { it.id }) { item ->
                        VaultCell(item, onLongPress = { pending = item })
                    }
                }
            }
        }
    }

    pending?.let { item ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(item.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = { Text("Remove this from the vault? The encrypted copy is deleted permanently.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        VaultManager.delete(context, item)
                        repo.deleteVaultItem(item)
                        pending = null
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VaultCell(item: VaultItem, onLongPress: () -> Unit) {
    val context = LocalContext.current
    var thumb by remember(item.id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(item.id) { thumb = VaultManager.thumbnail(context, item) }

    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(14.dp))
            .glass(RoundedCornerShape(14.dp))
            .combinedClickable(onClick = {}, onLongClick = onLongPress),
        contentAlignment = Alignment.Center
    ) {
        when {
            thumb != null -> Image(
                thumb!!, contentDescription = item.displayName,
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop
            )
            else -> Icon(
                when (item.kind) {
                    VaultKind.VIDEO -> Icons.Outlined.Videocam
                    VaultKind.AUDIO -> Icons.Outlined.AudioFile
                    else -> Icons.Outlined.Description
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}
