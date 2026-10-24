package com.sabid.applock.ui.history

import com.sabid.applock.ui.theme.glass
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sabid.applock.AppLockApp
import com.sabid.applock.core.data.LockEvent
import com.sabid.applock.core.util.AppInfoLoader
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Recent unlocks and failed attempts, newest first. Kept 90 days, 500 rows shown. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = AppLockApp.get().repo
    val events by repo.lockEvents.collectAsState(initial = emptyList())
    val labels = remember { mutableStateMapOf<String, String>() }

    LaunchedEffect(events) {
        events.map { it.packageName }.distinct().filter { it !in labels }.forEach {
            labels[it] = AppInfoLoader.label(context, it)
        }
    }
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        TopAppBar(
            title = { Text("Lock history") },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            },
            actions = {
                if (events.isNotEmpty()) {
                    IconButton(onClick = { scope.launch { repo.clearEvents() } }) {
                        Icon(Icons.Default.DeleteSweep, "Clear history")
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
        )
        if (events.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(40.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Nothing yet. Unlocks and wrong attempts will show up here.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(events, key = { it.id }) { e ->
                    val failed = e.kind == LockEvent.KIND_FAILED
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .glass(RoundedCornerShape(16.dp))
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(labels[e.packageName] ?: e.packageName, style = MaterialTheme.typography.titleMedium)
                            Text(
                                fmt.format(Date(e.at)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            when (e.kind) {
                                LockEvent.KIND_FAILED -> "Wrong attempt"
                                LockEvent.KIND_TIMED -> "Timed unlock"
                                else -> "Unlocked"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}
