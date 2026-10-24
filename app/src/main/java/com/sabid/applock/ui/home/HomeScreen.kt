package com.sabid.applock.ui.home

import com.sabid.applock.ui.theme.glass
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.sabid.applock.AppLockApp
import com.sabid.applock.core.data.AppSettings
import com.sabid.applock.core.model.InstalledApp
import com.sabid.applock.core.util.AppInfoLoader
import com.sabid.applock.core.util.Unlock
import com.sabid.applock.core.data.LockEvent
import com.sabid.applock.service.LockHistory
import com.sabid.applock.service.LockSession
import com.sabid.applock.ui.lock.UnlockSetupPrompt
import kotlinx.coroutines.launch

/** Which apps the list is showing. Independent of the search box and of [TypeFilter]. */
private enum class LockFilter(val label: String) { ALL("All"), LOCKED("Locked"), UNLOCKED("Unlocked") }

/** System apps are shown by default; hiding them is the common way to find "my" apps. */
private enum class TypeFilter(val label: String) { ANY("Any"), USER("Installed"), SYSTEM("System") }

/**
 * The apps list, and the place where the unlock question finally gets asked.
 *
 * [onSetUpSecret] is the route to the PIN/pattern editor. It is a callback rather than a
 * navigation call here because this screen is one tab of a NavHost it knows nothing about.
 */
@Composable
fun HomeScreen(activity: FragmentActivity, onSetUpSecret: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = AppLockApp.get().repo
    val store = AppLockApp.get().settings
    val lockedRows by repo.lockedApps.collectAsState(initial = emptyList())
    val locked = remember(lockedRows) { lockedRows.map { it.packageName }.toSet() }
    val settings by store.flow.collectAsState(initial = AppSettings())
    val status = rememberProtectionStatus(settings, locked.size)

    val events by repo.lockEvents.collectAsState(initial = emptyList())
    val intruders by repo.intruderLogs.collectAsState(initial = emptyList())
    val unlocksToday = remember(events) {
        val start = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        events.count { it.at >= start && it.kind != LockEvent.KIND_FAILED }
    }

    var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var lockFilter by remember { mutableStateOf(LockFilter.ALL) }
    var typeFilter by remember { mutableStateOf(TypeFilter.ANY) }
    var loading by remember { mutableStateOf(true) }

    // The setup question, and what the user had just locked when it was raised. Null label
    // means it was reached from the banner rather than from a switch.
    var askUnlockSetup by remember { mutableStateOf(false) }
    var askedAfterLocking by remember { mutableStateOf<String?>(null) }

    // Timed unlock: the app whose duration picker is open, and a ticker so "12 min left" ages.
    var timedTarget by remember { mutableStateOf<InstalledApp?>(null) }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(20_000); tick++ } }

    timedTarget?.let { app ->
        val left = LockSession.timedRemainingMs(app.packageName)
        AlertDialog(
            onDismissRequest = { timedTarget = null },
            title = { Text("Unlock ${app.label} for…") },
            text = {
                Column {
                    Text(
                        "It opens without a PIN until the time is up, even if you leave it " +
                            "or turn the screen off.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    listOf(15, 30, 60, 120).forEach { m ->
                        TextButton(onClick = {
                            LockSession.grantFor(app.packageName, m)
                            LockHistory.record(LockEvent.KIND_TIMED, app.packageName)
                            tick++
                            timedTarget = null
                        }) { Text(if (m < 60) "$m minutes" else "${m / 60} hour${if (m > 60) "s" else ""}") }
                    }
                    if (left > 0) {
                        TextButton(onClick = {
                            LockSession.revoke(app.packageName); tick++; timedTarget = null
                        }) { Text("Relock now") }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { timedTarget = null }) { Text("Cancel") } }
        )
    }

    LaunchedEffect(Unit) {
        apps = AppInfoLoader.loadLaunchable(context, locked)
        loading = false
    }

    /**
     * Locks [packages], then asks what AppLock should check — in that order.
     *
     * Locking first is deliberate. The user asked for the app to be locked; that is recorded
     * whatever they answer next, so declining leaves an honest "locked but not enforced" state
     * the banner can shout about, rather than silently discarding what they just asked for.
     *
     * Asked only while nothing is configured and nothing has been declined, which is what
     * "only once" means here: once an unlock exists, or once they have said no, flipping the
     * next forty switches asks nothing.
     */
    fun lockAndMaybeAsk(packages: Collection<String>, label: String?) {
        scope.launch {
            lockPackages(packages)
            if (!Unlock.chosen(settings) && !settings.unlockSetupDeclined) {
                askedAfterLocking = label
                askUnlockSetup = true
            }
        }
    }

    if (askUnlockSetup) {
        UnlockSetupPrompt(
            activity = activity,
            lockedLabel = askedAfterLocking,
            onChooseSecret = { askUnlockSetup = false; onSetUpSecret() },
            onUseDeviceCredential = {
                askUnlockSetup = false
                scope.launch { store.setDeviceCredentialUnlock(true) }
            },
            onDecline = {
                askUnlockSetup = false
                scope.launch { store.setUnlockSetupDeclined(true) }
            }
        )
    }

    // Lock state comes from the database rather than from whatever was true when the package
    // list was loaded, so toggling a switch does not need a reload to look right.
    val live = remember(apps, locked) {
        apps.map { it.copy(isLocked = it.packageName in locked) }
    }

    val suggestions = remember(live) {
        live.filter { !it.isLocked && !it.isSystem && looksSensitive(it) }.take(12)
    }

    val shown = remember(live, query, lockFilter, typeFilter) {
        live.asSequence()
            .filter { query.isBlank() || it.label.contains(query, ignoreCase = true) }
            .filter {
                when (lockFilter) {
                    LockFilter.ALL -> true
                    LockFilter.LOCKED -> it.isLocked
                    LockFilter.UNLOCKED -> !it.isLocked
                }
            }
            .filter {
                when (typeFilter) {
                    TypeFilter.ANY -> true
                    TypeFilter.USER -> !it.isSystem
                    TypeFilter.SYSTEM -> it.isSystem
                }
            }
            // Locked first only in the mixed view, where the grouping is the useful part. In a
            // single-state view it would just be an arbitrary reshuffle of an A-Z list.
            .sortedWith(
                if (lockFilter == LockFilter.ALL) {
                    compareByDescending<InstalledApp> { it.isLocked }.thenBy { it.label.lowercase() }
                } else {
                    compareBy { it.label.lowercase() }
                }
            )
            .toList()
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        ProtectionBanner(
            status,
            onSetUpUnlock = { askedAfterLocking = null; askUnlockSetup = true }
        )

        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 8.dp)) {
            Text(
                "Protected apps",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(2.dp))
            Text(
                // Never report a count on its own while the count means nothing.
                if (status.broken) "${locked.size} locked — not being enforced" else "Everything private stays private",
                style = MaterialTheme.typography.bodyMedium,
                color = if (status.broken) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )

            Spacer(Modifier.height(12.dp))
            SearchField(query) { query = it }

            Spacer(Modifier.height(10.dp))
            FilterBar(
                lockFilter = lockFilter,
                typeFilter = typeFilter,
                lockedCount = live.count { it.isLocked },
                unlockedCount = live.count { !it.isLocked },
                onLockFilter = { lockFilter = it },
                onTypeFilter = { typeFilter = it }
            )

            // Bulk actions act on what is *shown*, not on everything installed, so they
            // compose with the search box and the filters: type "bank", lock all four banking
            // apps. Acting on the whole list regardless of the filter would be a nasty
            // surprise.
            val bulkLock = shown.filter { !it.isLocked }
            val bulkUnlock = shown.filter { it.isLocked }
            if (bulkLock.isNotEmpty() || bulkUnlock.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (bulkLock.isNotEmpty()) {
                        BulkAction("Lock ${bulkLock.size}", Icons.Default.Lock) {
                            lockAndMaybeAsk(bulkLock.map { it.packageName }, null)
                        }
                    }
                    if (bulkUnlock.isNotEmpty()) {
                        BulkAction("Unlock ${bulkUnlock.size}", Icons.Default.LockOpen) {
                            scope.launch { bulkUnlock.forEach { repo.unlock(it.packageName) } }
                        }
                    }
                }
            }
        }

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }

            shown.isEmpty() -> Box(
                Modifier.fillMaxSize().padding(40.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (query.isBlank()) {
                        "No apps match these filters."
                    } else {
                        "No apps match \"$query\"."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }

            else -> LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Column {
                        Spacer(Modifier.height(14.dp))
                        StatsRow(
                            locked = locked.size,
                            unlocksToday = unlocksToday,
                            intruders = intruders.size
                        )
                        if (suggestions.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            SuggestionCard(suggestions) {
                                lockAndMaybeAsk(suggestions.map { it.packageName }, null)
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        ProfileBar(locked)
                        Spacer(Modifier.height(4.dp))
                    }
                }
                items(shown, key = { it.packageName }) { app ->
                    AppRow(
                        app,
                        timedLeftMs = if (app.isLocked) LockSession.timedRemainingMs(app.packageName) else 0L,
                        tick = tick,
                        onLongPress = { if (app.isLocked) timedTarget = app },
                        onToggle = { checked ->
                            if (checked) {
                                lockAndMaybeAsk(listOf(app.packageName), app.label)
                            } else {
                                scope.launch { repo.unlock(app.packageName) }
                            }
                        }
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        placeholder = { Text("Search apps") },
        leadingIcon = { Icon(Icons.Outlined.Search, null) },
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            focusedContainerColor = MaterialTheme.colorScheme.surface
        ),
        modifier = Modifier.fillMaxWidth()
    )
}

/**
 * Lock-state and app-type filters on one scrolling line.
 *
 * Counts are drawn from the whole list rather than the filtered one on purpose: a chip that
 * reported the size of the selection it produced would read "Locked 0" while showing every
 * locked app, and would tell the user nothing about what tapping it is going to do.
 */
@Composable
private fun FilterBar(
    lockFilter: LockFilter,
    typeFilter: TypeFilter,
    lockedCount: Int,
    unlockedCount: Int,
    onLockFilter: (LockFilter) -> Unit,
    onTypeFilter: (TypeFilter) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LockFilter.entries.forEach { filter ->
            FilterPill(
                label = filter.label,
                count = when (filter) {
                    LockFilter.ALL -> null
                    LockFilter.LOCKED -> lockedCount
                    LockFilter.UNLOCKED -> unlockedCount
                },
                selected = filter == lockFilter,
                onClick = { onLockFilter(filter) }
            )
        }

        Box(
            Modifier
                .padding(horizontal = 2.dp)
                .width(1.dp)
                .height(22.dp)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
        )

        TypeFilter.entries.forEach { filter ->
            FilterPill(
                label = filter.label,
                count = null,
                selected = filter == typeFilter,
                onClick = { onTypeFilter(filter) }
            )
        }
    }
}

@Composable
private fun FilterPill(label: String, count: Int?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surface
                }
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
        if (count != null) {
            Spacer(Modifier.width(6.dp))
            Text(
                count.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.75f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                }
            )
        }
    }
}

/**
 * Protects [packages] and drops any session grant they are holding.
 *
 * The revoke is the half that matters. A grant records "the user already unlocked this app in
 * this session", and it outlives the lock being switched off — so protecting an app that had
 * been unprotected and opened earlier in the same sitting used to quietly not challenge until
 * the next reboot. Switching a lock on has to mean the lock is on now.
 */
private suspend fun lockPackages(packages: Collection<String>) {
    AppLockApp.get().repo.lockAll(packages)
    packages.forEach { LockSession.revoke(it) }
}

@Composable
private fun BulkAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(7.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun AppRow(
    app: InstalledApp,
    timedLeftMs: Long,
    tick: Int,
    onLongPress: () -> Unit,
    onToggle: (Boolean) -> Unit
) {
    val context = LocalContext.current
    var icon by remember(app.packageName) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(app.packageName) { icon = AppInfoLoader.icon(context, app.packageName) }

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .glass(RoundedCornerShape(18.dp))
            .combinedClickable(onClick = { onToggle(!app.isLocked) }, onLongClick = onLongPress)
            .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center
        ) {
            icon?.let { Image(it, contentDescription = null, modifier = Modifier.size(30.dp)) }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                app.label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                when {
                    timedLeftMs > 0 -> "Unlocked for ${(timedLeftMs + 59_999) / 60_000} more min"
                    app.isLocked && app.isSystem -> "Locked · System app"
                    app.isLocked -> "Locked"
                    app.isSystem -> "System app"
                    else -> "Not locked"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (app.isLocked || timedLeftMs > 0) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
        Switch(checked = app.isLocked, onCheckedChange = onToggle)
    }
}

/** Heuristic: messaging, social, photo, banking and mail apps are what most people lock first. */
private val SENSITIVE_HINTS = listOf(
    "whatsapp", "telegram", "signal", "messenger", "messages", "sms", "instagram", "facebook",
    "snapchat", "twitter", "gallery", "photos", "bank", "pay", "wallet", "upi", "gmail",
    "mail", "files", "drive", "notes", "chat", "dating", "tinder"
)

private fun looksSensitive(app: InstalledApp): Boolean {
    val hay = (app.packageName + " " + app.label).lowercase()
    return SENSITIVE_HINTS.any { it in hay }
}

@Composable
private fun StatsRow(locked: Int, unlocksToday: Int, intruders: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        Stat("Locked", locked.toString(), Modifier.weight(1f))
        Stat("Unlocks today", unlocksToday.toString(), Modifier.weight(1f))
        Stat("Intruder shots", intruders.toString(), Modifier.weight(1f))
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    Column(
        modifier.glass(RoundedCornerShape(20.dp)).padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(
            value,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

@Composable
private fun SuggestionCard(apps: List<InstalledApp>, onLockAll: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(22.dp), strong = true)
            .padding(start = 16.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "Suggested to lock",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                apps.take(3).joinToString { it.label } + if (apps.size > 3) " +${apps.size - 3} more" else "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        Button(onClick = onLockAll) { Text("Lock ${apps.size}") }
    }
}
