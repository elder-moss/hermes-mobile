package com.eldermoss.hermes.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
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

    Scaffold(
        bottomBar = {
            if (configured) {
                NavigationBar {
                    for (tab in tabs) {
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(painterResource(tab.drawable), contentDescription = tab.label) },
                            label = { Text(tab.label) },
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
