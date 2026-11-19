package com.eldermoss.hermes.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.eldermoss.hermes.R
import com.eldermoss.hermes.ServiceLocator
import com.eldermoss.hermes.core.store.Settings
import com.eldermoss.hermes.feature.chat.ChatScreen
import com.eldermoss.hermes.feature.memory.MemoryScreen
import com.eldermoss.hermes.feature.schedules.SchedulesScreen
import com.eldermoss.hermes.feature.sessions.SessionsScreen
import com.eldermoss.hermes.feature.settings.SettingsScreen
import com.eldermoss.hermes.feature.skills.SkillsScreen

private data class Tab(val route: String, val label: String, val drawable: Int)

private val tabs = listOf(
    Tab("chat", "Chat", R.drawable.tab_chat),
    Tab("sessions", "Sessions", R.drawable.tab_sessions),
    Tab("skills", "Skills", R.drawable.tab_skills),
    Tab("schedules", "Schedules", R.drawable.tab_schedules),
    Tab("memory", "Memory", R.drawable.tab_memory),
    Tab("settings", "Settings", R.drawable.tab_settings),
)

@Composable
fun AppRoot(settings: Settings) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    val configured = ServiceLocator.settings.getCredentials().second.isNotBlank()
    // Memory is an optional add-on (needs a MemS sidecar): hide the tab until
    // both its URL and token are configured, so a stock Hermes user sees
    // only the features their server actually offers.
    val memsReady = settings.memsUrl.isNotBlank() && ServiceLocator.settings.getMemsToken().isNotBlank()
    val visibleTabs = if (memsReady) tabs else tabs.filter { it.route != "memory" }

    Scaffold(
        bottomBar = {
            if (configured) {
                // Six tabs don't fit with labels on a phone: icon-only, label on
                // the selected tab only, single-line so nothing ever wraps.
                NavigationBar {
                    for (tab in visibleTabs) {
                        val selected = currentRoute == tab.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(painterResource(tab.drawable), contentDescription = tab.label) },
                            label = if (selected) {
                                { Text(tab.label, maxLines = 1, style = MaterialTheme.typography.labelSmall) }
                            } else null,
                        )
                    }
                }
            }
        },
    ) { padding ->
        if (!configured) {
            // First run: no nav bar yet, so Settings IS the whole screen.
            SettingsScreen(Modifier.padding(padding))
        } else {
            NavHost(
                navController = nav,
                startDestination = "chat",
                modifier = Modifier.padding(padding),
            ) {
                composable("chat") { ChatScreen() }
                composable("chat/{sessionId}") { entry ->
                    ChatScreen(initialSessionId = entry.arguments?.getString("sessionId"))
                }
                composable("sessions") { SessionsScreen(onOpen = { id -> nav.navigate("chat/$id") }) }
                composable("skills") { SkillsScreen() }
                composable("schedules") { SchedulesScreen() }
                composable("memory") { MemoryScreen() }
                composable("settings") { SettingsScreen() }
            }
        }
    }
}
