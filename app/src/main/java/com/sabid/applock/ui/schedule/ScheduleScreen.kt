@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package com.sabid.applock.ui.schedule

import com.sabid.applock.ui.theme.glass
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sabid.applock.AppLockApp
import com.sabid.applock.core.data.TimeRule
import com.sabid.applock.core.model.InstalledApp
import com.sabid.applock.core.util.AppInfoLoader
import kotlinx.coroutines.launch

private val DAY_LABELS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
private const val EVERY_DAY = 0b1111111

/**
 * Time-based rules: "lock everything during school hours", "allow YouTube 4pm-5pm".
 *
 * The TimeRule table and the engine that reads it already existed but had no screen, so
 * no user could ever create a rule. Unreachable storage is worse than no storage — it
 * looks like a working feature in the schema and silently isn't one.
 */
@Composable
fun ScheduleScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = AppLockApp.get().repo
    val scope = rememberCoroutineScope()
    val rules by repo.timeRules.collectAsState(initial = emptyList())
    val lockedRows by repo.lockedApps.collectAsState(initial = emptyList())

    var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    LaunchedEffect(lockedRows) {
        val locked = lockedRows.map { it.packageName }.toSet()
        // Only protected apps can be scoped by a rule: a rule naming an unprotected app
        // could only ever be a no-op, and offering it would imply otherwise.
        apps = AppInfoLoader.loadLaunchable(context, locked).filter { it.packageName in locked }
    }

    var editing by remember { mutableStateOf<TimeRule?>(null) }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Text(
                "Schedules",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(start = 4.dp, top = 12.dp)
            )
            Text(
                "Lock or allow chosen apps at set times. Lock always wins where two rules overlap.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 16.dp)
            )

            if (rules.isEmpty()) {
                Text(
                    "No schedules yet. Apps stay locked whenever protection is on.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }

            rules.forEach { rule ->
                RuleCard(
                    rule = rule,
                    onToggle = { scope.launch { repo.upsertRule(rule.copy(enabled = it)) } },
                    onEdit = { editing = rule },
                    onDelete = { scope.launch { repo.deleteRule(rule) } }
                )
                Spacer(Modifier.height(8.dp))
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    editing = TimeRule(
                        label = "New schedule",
                        startMinuteOfDay = 9 * 60,
                        endMinuteOfDay = 15 * 60,
                        daysMask = 0b0011111, // weekdays
                        locksInside = true
                    )
                },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                Icon(Icons.Outlined.Add, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Add schedule", style = MaterialTheme.typography.labelLarge)
            }
            TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
            Spacer(Modifier.height(40.dp))
        }
    }

    editing?.let { draft ->
        RuleEditor(
            initial = draft,
            apps = apps,
            onDismiss = { editing = null },
            onSave = {
                scope.launch { repo.upsertRule(it) }
                editing = null
            }
        )
    }
}

@Composable
private fun RuleCard(
    rule: TimeRule,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .glass(RoundedCornerShape(16.dp))
            .clickable(onClick = onEdit)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                rule.label.ifBlank { "Schedule" },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                buildString {
                    append(if (rule.locksInside) "Locks " else "Allows ")
                    append(
                        if (rule.packages.isEmpty()) "all protected apps"
                        else "${rule.packages.size} app${if (rule.packages.size == 1) "" else "s"}"
                    )
                    append(" · ")
                    append(formatTime(rule.startMinuteOfDay))
                    append("–")
                    append(formatTime(rule.endMinuteOfDay))
                    append(" · ")
                    append(formatDays(rule.daysMask))
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = rule.enabled, onCheckedChange = onToggle)
        Icon(
            Icons.Outlined.DeleteOutline,
            contentDescription = "Delete schedule",
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier
                .padding(start = 8.dp)
                .size(22.dp)
                .clickable(onClick = onDelete)
        )
    }
}

@Composable
private fun RuleEditor(
    initial: TimeRule,
    apps: List<InstalledApp>,
    onDismiss: () -> Unit,
    onSave: (TimeRule) -> Unit
) {
    var label by remember { mutableStateOf(initial.label) }
    var days by remember { mutableStateOf(initial.daysMask) }
    var locks by remember { mutableStateOf(initial.locksInside) }
    var chosen by remember { mutableStateOf(initial.packages) }
    var start by remember { mutableStateOf(initial.startMinuteOfDay) }
    var end by remember { mutableStateOf(initial.endMinuteOfDay) }
    var picking by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id == 0L) "New schedule" else "Edit schedule") },
        confirmButton = {
            TextButton(
                // A rule with no days can never fire, and a zero-length window is a
                // rule that is always off. Blocking both here beats saving something
                // that looks configured and quietly does nothing.
                enabled = days != 0 && start != end,
                onClick = {
                    onSave(
                        initial.copy(
                            label = label.trim().ifBlank { "Schedule" },
                            startMinuteOfDay = start,
                            endMinuteOfDay = end,
                            daysMask = days,
                            locksInside = locks,
                            packagesCsv = chosen.joinToString(",")
                        )
                    )
                }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Name") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Lock", locks) { locks = true }
                    Chip("Allow", !locks) { locks = false }
                }

                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("From ${formatTime(start)}", false) { picking = "start" }
                    Chip("To ${formatTime(end)}", false) { picking = "end" }
                }
                if (end < start) {
                    Text(
                        "This window runs past midnight into the next day.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }

                Spacer(Modifier.height(14.dp))
                Text("Days", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DAY_LABELS.forEachIndexed { index, name ->
                        val bit = 1 shl index
                        Chip(name, days and bit != 0) { days = days xor bit }
                    }
                }
                TextButton(onClick = { days = EVERY_DAY }) { Text("Every day") }

                Spacer(Modifier.height(8.dp))
                Text("Apps", style = MaterialTheme.typography.labelLarge)
                Text(
                    if (chosen.isEmpty()) "All protected apps" else "${chosen.size} selected",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                if (apps.isEmpty()) {
                    Text(
                        "Lock some apps first and they'll appear here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        apps.forEach { app ->
                            Chip(app.label, app.packageName in chosen) {
                                chosen = if (app.packageName in chosen) {
                                    chosen - app.packageName
                                } else {
                                    chosen + app.packageName
                                }
                            }
                        }
                    }
                }
            }
        }
    )

    picking?.let { which ->
        val current = if (which == "start") start else end
        TimePickerDialog(
            initialMinuteOfDay = current,
            onDismiss = { picking = null },
            onConfirm = { minutes ->
                if (which == "start") start = minutes else end = minutes
                picking = null
            }
        )
    }
}

@Composable
private fun TimePickerDialog(
    initialMinuteOfDay: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    val state = rememberTimePickerState(
        initialHour = initialMinuteOfDay / 60,
        initialMinute = initialMinuteOfDay % 60,
        is24Hour = false
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour * 60 + state.minute) }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = { TimePicker(state = state) }
    )
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun formatTime(minuteOfDay: Int): String {
    val h = (minuteOfDay / 60).coerceIn(0, 23)
    val m = (minuteOfDay % 60).coerceIn(0, 59)
    return "%02d:%02d".format(h, m)
}

private fun formatDays(mask: Int): String = when {
    mask and EVERY_DAY == EVERY_DAY -> "Every day"
    mask and 0b0011111 == 0b0011111 && mask and 0b1100000 == 0 -> "Weekdays"
    mask and 0b1100000 == 0b1100000 && mask and 0b0011111 == 0 -> "Weekends"
    else -> DAY_LABELS.filterIndexed { i, _ -> mask and (1 shl i) != 0 }.joinToString(" ")
}
