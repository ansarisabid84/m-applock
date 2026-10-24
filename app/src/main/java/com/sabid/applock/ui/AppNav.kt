package com.sabid.applock.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.sabid.applock.ui.theme.glass
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.fragment.app.FragmentActivity
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.sabid.applock.ui.browser.BrowserScreen
import com.sabid.applock.ui.history.HistoryScreen
import com.sabid.applock.ui.home.HomeScreen
import com.sabid.applock.ui.intruder.IntruderScreen
import com.sabid.applock.ui.schedule.ScheduleScreen
import com.sabid.applock.ui.settings.ChangeSecretScreen
import com.sabid.applock.ui.settings.SettingsScreen
import com.sabid.applock.ui.vault.VaultScreen

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("apps", "Apps", Icons.Outlined.Lock),
    Tab("vault", "Vault", Icons.Outlined.PhotoLibrary),
    Tab("intruders", "Intruders", Icons.Outlined.Visibility),
    Tab("settings", "Settings", Icons.Outlined.Tune)
)

@Composable
fun AppNav(activity: FragmentActivity) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val current = entry?.destination?.route

    val hideChrome = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.CompositionLocalProvider(
        com.sabid.applock.ui.theme.LocalHideChrome provides hideChrome
    ) {
    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            if (!hideChrome.value && tabs.any { it.route == current }) Row(
                Modifier
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 10.dp)
                    .fillMaxWidth()
                    .glass(RoundedCornerShape(32.dp), strong = true)
                    .padding(6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                tabs.forEach { tab ->
                    val selected = current == tab.route
                    Row(
                        Modifier
                            .weight(if (selected) 1.7f else 1f)
                            .height(48.dp)
                            .clip(RoundedCornerShape(26.dp))
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                                else Color.Transparent
                            )
                            .clickable {
                                if (!selected) {
                                    nav.navigate(tab.route) {
                                        popUpTo("apps") { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            }
                            .animateContentSize(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            tab.icon,
                            contentDescription = tab.label,
                            tint = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (selected) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                tab.label,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    ) { padding ->
        NavHost(nav, startDestination = "apps", modifier = Modifier.padding(padding)) {
            composable("apps") {
                HomeScreen(
                    activity = activity,
                    onSetUpSecret = { nav.navigate("secret") }
                )
            }
            composable("vault") { VaultScreen() }
            composable("intruders") { IntruderScreen() }
            composable("settings") {
                SettingsScreen(
                    activity = activity,
                    onOpenBrowser = { nav.navigate("browser") },
                    onChangeSecret = { nav.navigate("secret") },
                    onOpenSchedules = { nav.navigate("schedules") },
                    onOpenHistory = { nav.navigate("history") }
                )
            }
            composable("secret") {
                ChangeSecretScreen(activity = activity, onDone = { nav.popBackStack() })
            }
            composable("schedules") { ScheduleScreen(onBack = { nav.popBackStack() }) }
            composable("history") { HistoryScreen(onBack = { nav.popBackStack() }) }
            composable("browser") { BrowserScreen(onBack = { nav.popBackStack() }) }
        }
    }
    }
}
