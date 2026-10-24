@file:OptIn(ExperimentalLayoutApi::class)
package com.sabid.applock.ui.settings

import com.sabid.applock.ui.theme.glass
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoDelete
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.PhonelinkLock
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.sabid.applock.AppLockApp
import com.sabid.applock.core.data.AppSettings
import com.sabid.applock.core.model.LockMethod
import com.sabid.applock.core.model.RelockPolicy
import com.sabid.applock.core.model.ThemeSkin
import com.sabid.applock.core.util.OemSettings
import com.sabid.applock.core.util.Permissions
import com.sabid.applock.core.util.RuntimeGrant
import com.sabid.applock.core.util.SpecialGrant
import com.sabid.applock.core.util.UninstallGuard
import com.sabid.applock.core.util.Unlock
import com.sabid.applock.service.LockGuardService
import com.sabid.applock.service.LockSession
import com.sabid.applock.ui.home.rememberProtectionStatus
import com.sabid.applock.ui.lock.Biometrics
import com.sabid.applock.ui.permissions.LocalPermissionRequester
import com.sabid.applock.ui.permissions.SensitiveGrant
import com.sabid.applock.ui.permissions.rememberAccessibilityDisclosure
import com.sabid.applock.ui.permissions.rememberConsent
import com.sabid.applock.ui.permissions.rememberPermissionState
import kotlinx.coroutines.launch

/**
 * [activity] is needed for one row only: switching the phone's own screen lock on requires
 * hosting a BiometricPrompt, which needs a FragmentActivity rather than a bare Context.
 */
@Composable
fun SettingsScreen(
    activity: FragmentActivity,
    onOpenBrowser: () -> Unit,
    onChangeSecret: () -> Unit,
    onOpenSchedules: () -> Unit,
    onOpenHistory: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = AppLockApp.get().settings
    val s by store.flow.collectAsState(initial = AppSettings())
    val rules by AppLockApp.get().repo.timeRules.collectAsState(initial = emptyList())
    val locked by AppLockApp.get().repo.lockedApps.collectAsState(initial = emptyList())
    val perms = rememberPermissionState()
    val status = rememberProtectionStatus(s, locked.size)
    val requester = LocalPermissionRequester.current
    var openFailed by remember { mutableStateOf(false) }

    // The accessibility grant is only ever requested through its prominent disclosure —
    // a Play requirement, and the reason the previous submission was rejected.
    val askAccessibility = rememberAccessibilityDisclosure { opened -> openFailed = !opened }
    val consent = rememberConsent()

    // Device-admin state is never cached in settings — DevicePolicyManager is the only truth
    // and the user can change it from outside AppLock at any time. This counter is just a
    // nudge to re-read it after something that could have changed it.
    var adminTick by remember { mutableIntStateOf(0) }
    val adminActive = remember(adminTick) { UninstallGuard.isActive(context) }
    val adminLauncher = rememberLauncherForActivityResult(StartActivityForResult()) {
        // The result code is not trustworthy across ROMs, so re-read rather than infer.
        adminTick++
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Text(
            "Settings",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 16.dp)
        )

        // The master switch is lifted out of the list because it is not a peer of the other
        // rows: with it off, nothing else on this screen has any effect. Making that visible
        // at a glance is worth the extra card.
        ProtectionCard(
            enabled = s.protectionEnabled,
            lockedCount = locked.size,
            onChange = {
                scope.launch {
                    store.setProtection(it)
                    if (it) LockGuardService.start(context) else LockSession.revokeAll()
                }
            }
        )

        Section("Protection", Icons.Default.Shield) {
            SwitchRow(
                Icons.Default.Apps,
                "Lock newly installed apps",
                "Anything you install later starts protected",
                s.lockNewApps
            ) { scope.launch { store.setLockNewApps(it) } }
            RowDivider()
            NavRow(
                Icons.Default.Schedule,
                "Schedules",
                when {
                    rules.isEmpty() -> "Lock or allow apps at set times"
                    else -> {
                        val on = rules.count { it.enabled }
                        "${rules.size} rule${if (rules.size == 1) "" else "s"}, $on active"
                    }
                }
            ) { onOpenSchedules() }
            RowDivider()
            NavRow(
                Icons.Default.History,
                "Lock history",
                "Recent unlocks and wrong attempts"
            ) { onOpenHistory() }
            RowDivider()
            SwitchRow(
                Icons.Default.Warning,
                "Fake \"not responding\" screen",
                "Shows a crash dialog instead of the keypad. Long-press the message to unlock.",
                s.fakeCrash
            ) { scope.launch { store.setFakeCrash(it) } }
        }

        Section("Unlocking", Icons.Default.Password) {
            // First, because it decides which of the two things below is actually in force.
            SwitchRow(
                Icons.Default.PhonelinkLock,
                "Use my phone's screen lock",
                when {
                    s.deviceCredentialUnlock && s.secret != null ->
                        "In use. Switching this off goes back to your AppLock PIN or pattern."
                    s.deviceCredentialUnlock ->
                        "In use. Switch it off and nothing will be locked until you set an " +
                            "AppLock PIN or pattern."
                    !status.phoneScreenLockSet ->
                        "Unavailable — your phone has no PIN, pattern or password set."
                    else ->
                        "Instead of a separate AppLock secret. The trade-off: anyone who can " +
                            "unlock your phone could then open your locked apps."
                },
                checked = s.deviceCredentialUnlock,
                // Always allow switching off, even if the phone lock has since vanished —
                // otherwise the one setting that could rescue that state would be frozen.
                enabled = status.phoneScreenLockSet || s.deviceCredentialUnlock
            ) { wanted ->
                if (!wanted) {
                    scope.launch { store.setDeviceCredentialUnlock(false) }
                } else {
                    // Confirmed before it is stored, not after. This is the only way to learn
                    // that the credential prompt actually works on this ROM while an unlock
                    // that does work is still configured.
                    Biometrics.promptDeviceCredential(
                        activity = activity,
                        title = "Use your phone's screen lock",
                        subtitle = "Confirm it once to switch this on",
                        allowBiometric = false,
                        onSuccess = { scope.launch { store.setDeviceCredentialUnlock(true) } },
                        onFailure = {}
                    )
                }
            }
            RowDivider()
            NavRow(
                Icons.Default.Dialpad,
                if (s.secret == null) "Set a PIN or pattern" else "Change PIN or pattern",
                when {
                    // Says which secret is *in force*, not merely which one is stored. A
                    // phone-screen-lock user may still have an old hash on disk, and reporting
                    // "4-digit PIN" for a PIN AppLock will not accept is the kind of small lie
                    // that makes people distrust the rest of the screen.
                    s.deviceCredentialUnlock && s.secret != null ->
                        "Set, but your phone's screen lock is in use instead"
                    s.deviceCredentialUnlock -> "Not set — your phone's screen lock is in use"
                    s.secret == null -> "Not set — nothing is being locked"
                    else -> when (s.lockMethod) {
                        LockMethod.PIN -> "${s.pinLength}-digit PIN"
                        LockMethod.PATTERN -> "Pattern"
                    }
                },
                highlight = !Unlock.chosen(s)
            ) { onChangeSecret() }
            RowDivider()
            NavRow(
                Icons.Default.HelpOutline,
                "Recovery question",
                s.recoveryQuestion ?: when {
                    // Recovery resets the AppLock secret, which is not what is standing in the
                    // way in this mode, so its absence is not a gap worth flagging.
                    s.deviceCredentialUnlock ->
                        "Not needed while your phone's screen lock is in use"
                    else -> "Not set — a forgotten PIN cannot be recovered"
                },
                highlight = s.recoveryQuestion == null && !s.deviceCredentialUnlock
            ) { onChangeSecret() }
            RowDivider()
            SwitchRow(
                Icons.Default.Fingerprint,
                "Fingerprint / face",
                "Offer biometrics before the keypad",
                s.biometricEnabled
            ) { scope.launch { store.setBiometric(it) } }
            RowDivider()
            SwitchRow(
                Icons.Default.Dialpad,
                "Scrambled keypad",
                "Shuffle the digits so no one can read your PIN over your shoulder",
                s.randomKeyboard
            ) { scope.launch { store.setRandomKeyboard(it) } }
            RowDivider()
            SwitchRow(
                Icons.Default.Gesture,
                "Invisible pattern",
                "Draw with no visible trail",
                s.invisiblePattern
            ) { scope.launch { store.setInvisiblePattern(it) } }
            RowDivider()
            SwitchRow(
                Icons.Default.Vibration,
                "Haptic feedback",
                "Buzz on each key",
                s.vibrateOnKey
            ) { scope.launch { store.setVibrate(it) } }
        }

        Section("When to ask again", Icons.Default.Timer) {
            ChoiceRow(
                options = listOf(
                    "Every time" to RelockPolicy.IMMEDIATELY,
                    "Until screen off" to RelockPolicy.SCREEN_OFF,
                    "Brief exit allowed" to RelockPolicy.TIMED,
                    "Until restart" to RelockPolicy.RESTART
                ),
                selected = s.relockPolicy,
                onSelect = { scope.launch { store.setRelockPolicy(it) } }
            )
            if (s.relockPolicy == RelockPolicy.TIMED) {
                StepperRow("Brief exit window", "${s.briefExitMinutes} min", s.briefExitMinutes, 1, 30) {
                    scope.launch { store.setBriefExitMinutes(it) }
                }
            }
        }

        Section("Intruder selfie", Icons.Default.PhotoCamera) {
            // Said out loud rather than left to be discovered. Everything in this section is
            // driven by AppLock counting wrong entries, and in phone-screen-lock mode it never
            // sees one: the credential screen belongs to the system, which does its own
            // throttling and reports only success. Leaving these switches looking armed would
            // be promising a photo that will never be taken.
            if (s.deviceCredentialUnlock) {
                Hint(
                    "None of this fires while your phone's screen lock is in use. Wrong " +
                        "attempts go to the system's own lock prompt, which handles them " +
                        "itself and doesn't tell AppLock about them.",
                    warning = true
                )
            }
            SwitchRow(
                Icons.Default.PhotoCamera,
                "Photograph failed attempts",
                "Front camera only, kept out of your gallery, never uploaded",
                s.intruderEnabled
            ) { scope.launch { store.setIntruder(it) } }

            if (s.intruderEnabled) {
                RowDivider()
                StepperRow(
                    "Start photographing after",
                    "${s.intruderAfterAttempts} wrong ${tries(s.intruderAfterAttempts)}, then every try after that",
                    s.intruderAfterAttempts, 1, 10
                ) { scope.launch { store.setIntruderAfter(it) } }

                RowDivider()
                SwitchRow(
                    Icons.Default.AutoDelete,
                    "Delete photos automatically",
                    if (s.intruderAutoDelete) {
                        "Photos older than ${s.intruderRetentionDays} days are deleted. Pin a " +
                            "photo in the Intruders tab to keep it for good."
                    } else {
                        "Photos are kept until you delete them yourself"
                    },
                    s.intruderAutoDelete
                ) { scope.launch { store.setIntruderAutoDelete(it) } }

                if (s.intruderAutoDelete) {
                    ChoiceRow(
                        options = listOf(
                            "7 days" to 7,
                            "14 days" to 14,
                            "30 days" to 30,
                            "90 days" to 90,
                            "1 year" to 365
                        ),
                        selected = s.intruderRetentionDays,
                        onSelect = { scope.launch { store.setIntruderRetentionDays(it) } }
                    )
                }
            }

            RowDivider()
            // This setting has existed and been honoured by FailedAttempt since the start,
            // with nothing anywhere in the UI able to turn it on. A security response the
            // user cannot reach is the same as one that does not exist.
            SwitchRow(
                Icons.Default.CleaningServices,
                "Clear vault cache after repeated failures",
                "Wipe decrypted vault files and every unlocked session. Your PIN, your " +
                    "app list and the vault itself are untouched.",
                s.panicWipeAfterAttempts > 0
            ) { on -> scope.launch { store.setPanicWipe(if (on) 5 else 0) } }
            if (s.panicWipeAfterAttempts > 0) {
                StepperRow(
                    "Clear after",
                    "${s.panicWipeAfterAttempts} wrong ${tries(s.panicWipeAfterAttempts)}",
                    s.panicWipeAfterAttempts, 1, 20
                ) { scope.launch { store.setPanicWipe(it) } }
            }
        }

        Section("Appearance", Icons.Default.Palette) {
            ChoiceRow(
                options = ThemeSkin.entries.map {
                    it.name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase) to it
                },
                selected = s.theme,
                onSelect = { scope.launch { store.setTheme(it) } }
            )
        }

        Section("Tamper protection", Icons.Default.AdminPanelSettings) {
            SwitchRow(
                Icons.Default.VisibilityOff,
                "Hide locked apps from recents",
                // Worded around what the user actually sees, including the cost. Android
                // snapshots an app the moment it stops being visible and shows that snapshot
                // in the app switcher; the only way to have nothing to show is for the app to
                // be closed before that happens, which means reopening it afterwards.
                "Close a locked app as soon as the lock screen appears, so the app switcher " +
                    "has no picture of it to show. It reopens at its main screen after you " +
                    "unlock.",
                s.hideFromRecents
            ) { scope.launch { store.setHideFromRecents(it) } }

            if (s.hideFromRecents) {
                RowDivider()
                SwitchRow(
                    Icons.Default.Layers,
                    "Close them again when you leave",
                    // The cost is stated first, because it is the part the user will notice
                    // and wonder about. An app they unlocked and used gets snapshotted with
                    // its contents the moment they press home, and emptying its task is the
                    // only way to take that picture back.
                    "The screen goes black for a moment when you leave a locked app, while " +
                        "AppLock closes it. Without this, the app switcher keeps showing " +
                        "whatever you were looking at.",
                    s.closeLockedOnLeave
                ) { scope.launch { store.setCloseLockedOnLeave(it) } }
            }

            RowDivider()
            NavRow(
                Icons.Default.AdminPanelSettings,
                "Uninstall protection",
                if (adminActive) {
                    "On — AppLock cannot be uninstalled. Tap to turn off."
                } else {
                    "Off — anyone can uninstall AppLock and remove every lock"
                },
                highlight = !adminActive
            ) {
                if (adminActive) {
                    UninstallGuard.deactivate(context)
                    adminTick++
                } else {
                    // Started with the result callback rather than fire-and-forget so the row
                    // updates the moment the system dialog closes. isAdminActive is read live
                    // — there is no flag to get out of step with the platform.
                    consent(SensitiveGrant.DEVICE_ADMIN) {
                        adminLauncher.launch(UninstallGuard.activationIntent(context))
                    }
                }
            }

            if (adminActive) {
                RowDivider()
                SwitchRow(
                    Icons.Default.Shield,
                    "Lock Settings and the installer",
                    "Ask for your PIN before Settings, the app installer or your phone's " +
                        "security app can be opened",
                    s.guardSystemPages
                ) { scope.launch { store.setGuardSystemPages(it) } }
                if (!s.guardSystemPages) {
                    Hint(
                        "Uninstall protection can still be switched off from Settings > " +
                            "Security > Device admin apps. Turn this on to put the PIN in " +
                            "front of that too."
                    )
                }
            }

            if (s.recoveryQuestion == null) {
                Hint(
                    "Set a recovery question first. With uninstall protection on and no " +
                        "recovery question, a forgotten PIN cannot be reset and AppLock " +
                        "cannot be removed.",
                    warning = true
                )
            }
        }

        Section("Permissions", Icons.Default.Layers) {
            // Read through rememberPermissionState so the labels refresh when the user
            // returns from a Settings page. Calling Permissions.hasX() inline during
            // composition left every row showing whatever was true at first draw.
            NavRow(
                Icons.Default.Accessibility,
                "App detection",
                // "Granted" and "running" are different facts. Reporting only the grant is
                // how a phone that had silently killed the service could still show
                // "Active" here while nothing was being locked.
                when {
                    !perms.accessibility -> "Off — apps will not lock"
                    status.accessibilityRunning -> "Active"
                    !status.accessibilitySettled -> "Starting up…"
                    status.fallbackDetection ->
                        "Stopped by your phone — backup detection is covering it"
                    else -> "Granted, but stopped by your phone — tap to re-enable"
                },
                // Not a problem while the service is still binding, and not an emergency
                // while usage-stats polling is standing in for it.
                highlight = perms.accessibility &&
                    !status.accessibilityRunning &&
                    status.accessibilitySettled &&
                    !status.fallbackDetection
            ) { askAccessibility() }
            if (status.autostartPageAvailable) {
                RowDivider()
                NavRow(
                    Icons.Default.RestartAlt,
                    "Autostart / background restrictions",
                    "${OemSettings.vendorName} restricts background apps separately from " +
                        "Android — allow AppLock here or it will keep being stopped"
                ) {
                    consent(SensitiveGrant.AUTOSTART) { openFailed = !Permissions.open(context, SpecialGrant.AUTOSTART) }
                }
            }
            RowDivider()
            NavRow(
                Icons.Default.Layers,
                "Display over other apps",
                if (perms.overlay) "Allowed" else "Blocked — the lock screen cannot appear",
                highlight = !perms.overlay
            ) {
                consent(SensitiveGrant.OVERLAY) { openFailed = !Permissions.open(context, SpecialGrant.OVERLAY) }
            }
            RowDivider()
            NavRow(
                Icons.Default.QueryStats,
                "Usage access",
                if (perms.usageAccess) "Granted — backup detection on" else "Off — no backup detection"
            ) {
                consent(SensitiveGrant.USAGE_ACCESS) { openFailed = !Permissions.open(context, SpecialGrant.USAGE_ACCESS) }
            }
            RowDivider()
            NavRow(
                Icons.Default.BatteryFull,
                "Battery optimisation",
                if (perms.batteryExempt) "Exempt" else "On — Android may suspend protection",
                highlight = !perms.batteryExempt
            ) {
                consent(SensitiveGrant.BATTERY) { openFailed = !Permissions.open(context, SpecialGrant.BATTERY) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                RowDivider()
                NavRow(
                    Icons.Default.Notifications,
                    "Notifications",
                    if (perms.notifications) "Allowed" else "Blocked — no protection badge or alerts"
                ) {
                    if (perms.notifications) return@NavRow
                    consent(SensitiveGrant.NOTIFICATIONS) { requester?.request(listOf(RuntimeGrant.NOTIFICATIONS), context) }
                }
            }
            if (Permissions.hasCameraHardware(context)) {
                RowDivider()
                NavRow(
                    Icons.Default.CameraAlt,
                    "Camera",
                    if (perms.camera) "Allowed — intruder selfies work" else "Blocked — no intruder selfies",
                    highlight = s.intruderEnabled && !perms.camera
                ) {
                    if (perms.camera) return@NavRow
                    consent(SensitiveGrant.CAMERA) { requester?.request(listOf(RuntimeGrant.CAMERA), context) }
                }
            }
            if (openFailed) {
                Hint(
                    "This phone would not open that page directly. Tap below to open " +
                        "AppLock's own settings page and grant it from there.",
                    warning = true
                )
                NavRow(Icons.Default.Info, "Open app info", "Settings > Apps > AppLock") {
                    Permissions.openAppDetails(context)
                }
            }
            RowDivider()
            SwitchRow(
                Icons.Default.Bolt,
                "Power saving mode",
                "Relax background checks; slightly slower to lock",
                s.powerSaver
            ) { scope.launch { store.setPowerSaver(it) } }
        }

        Section("Tools", Icons.Default.Language) {
            NavRow(
                Icons.Default.Language,
                "Private browser",
                "No history, no cookies, cleared on exit"
            ) { onOpenBrowser() }
        }

        Section("About", Icons.Default.Info) {
            val versionLabel = remember {
                runCatching {
                    val info = context.packageManager.getPackageInfo(context.packageName, 0)
                    "v${info.versionName}"
                }.getOrDefault("")
            }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "AppLock",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    if (versionLabel.isNotEmpty()) {
                        Text(
                            versionLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                                .padding(horizontal = 12.dp, vertical = 5.dp)
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "AppLock puts a lock screen in front of the apps you choose, so only you " +
                        "can open them. Everything runs on this phone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            RowDivider()
            AboutPoint(
                "What it can do",
                "Lock apps with a PIN, pattern or your phone's screen lock. Unlock an app for " +
                    "a set time, switch between lock profiles, lock by schedule, photograph " +
                    "wrong attempts, keep files in an encrypted vault, and browse privately."
            )
            AboutPoint(
                "Your privacy",
                "No ads, no analytics, no account and no server. The only network use is the " +
                    "private browser loading pages you type in. Your PIN is kept only as a " +
                    "salted PBKDF2-SHA256 hash, and vault files use AES-256-GCM with a key " +
                    "that never leaves your phone's secure hardware."
            )
            AboutPoint(
                "Permissions",
                "AppLock explains every sensitive permission and asks for your agreement " +
                    "before requesting it. You can withdraw any of them in Android's settings " +
                    "at any time; some features simply stop working without them."
            )
            RowDivider()
            Copyright()
        }

        Spacer(Modifier.height(40.dp))
    }
}

private fun tries(n: Int) = if (n == 1) "try" else "tries"

/** The master switch, given its own card because everything below it depends on it. */
@Composable
private fun ProtectionCard(enabled: Boolean, lockedCount: Int, onChange: (Boolean) -> Unit) {
    val accent = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(accent.copy(alpha = 0.12f))
            .clickable { onChange(!enabled) }
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(accent.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Shield, contentDescription = null, tint = accent)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (enabled) "Protection active" else "Protection off",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                when {
                    !enabled -> "Nothing is being locked right now"
                    lockedCount == 0 -> "No apps locked yet — pick some in the Apps tab"
                    else -> "$lockedCount app${if (lockedCount == 1) "" else "s"} locked"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = enabled, onCheckedChange = onChange)
    }
}

@Composable
private fun Section(title: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    Row(
        Modifier.padding(start = 6.dp, top = 26.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(8.dp))
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .glass(RoundedCornerShape(22.dp))
            .padding(vertical = 6.dp),
        content = content
    )
}

/** Hairline between rows inside a card. Inset so it reads as a separator, not a border. */
@Composable
private fun RowDivider() {
    Box(
        Modifier
            .padding(start = 62.dp, end = 16.dp)
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
    )
}

/** Leading icon in a tinted square, the shape every row in every card shares. */
@Composable
private fun RowIcon(icon: ImageVector, tint: Color = MaterialTheme.colorScheme.primary) {
    Box(
        Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(tint.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = tint)
    }
}

@Composable
private fun SwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onChange(!checked) }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RowIcon(icon)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(10.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun NavRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    highlight: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RowIcon(icon, if (highlight) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = if (highlight) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(10.dp))
        Icon(
            Icons.AutoMirrored.Outlined.OpenInNew, null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun <T> ChoiceRow(options: List<Pair<String, T>>, selected: T, onSelect: (T) -> Unit) {
    FlowRowCompat {
        options.forEach { (label, value) ->
            val active = value == selected
            Box(
                Modifier
                    .padding(4.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (active) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                        }
                    )
                    .clickable { onSelect(value) }
                    .padding(horizontal = 14.dp, vertical = 9.dp)
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun FlowRowCompat(content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), content = content)
}

@Composable
private fun StepperRow(title: String, value: String, current: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(start = 48.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        StepButton("−", current > min) { if (current > min) onChange(current - 1) }
        Spacer(Modifier.width(8.dp))
        StepButton("+", current < max) { if (current < max) onChange(current + 1) }
    }
}

@Composable
private fun StepButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 0.24f else 0.08f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.35f)
        )
    }
}

@Composable
private fun Hint(text: String, warning: Boolean = false) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        if (warning) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                modifier = Modifier.padding(top = 2.dp).size(16.dp),
                tint = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.width(10.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** "© {year} {name} All rights reserved", with the name linking to the author's LinkedIn. */
@Composable
private fun Copyright() {
    val year = remember { java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) }
    val link = MaterialTheme.colorScheme.primary
    val text = remember(year, link) {
        androidx.compose.ui.text.buildAnnotatedString {
            append("\u00A9 $year ")
            withLink(
                androidx.compose.ui.text.LinkAnnotation.Url(
                    "https://www.linkedin.com/in/ansarisabid/",
                    androidx.compose.ui.text.TextLinkStyles(
                        SpanStyle(color = link, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline)
                    )
                )
            ) { append("Sabid Ansari") }
            append(" All rights reserved")
        }
    }
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
    )
}

@Composable
private fun AboutPoint(title: String, body: String) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(2.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
