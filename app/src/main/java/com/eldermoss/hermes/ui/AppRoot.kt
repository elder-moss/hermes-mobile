package com.eldermoss.hermes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
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
                // Custom bar: 54dp icon row + FULL gesture-bar inset (half overlapped the pill).
                val density = androidx.compose.ui.platform.LocalDensity.current
                val insetDp = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(bottom = insetDp),
                ) {
                    androidx.compose.material3.HorizontalDivider(
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                        thickness = 0.5.dp,
                    )
                    Row(
                        Modifier.fillMaxWidth().height(54.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        for (tab in visibleTabs) {
                            val selected = currentRoute == tab.route
                            Box(
                                modifier = Modifier
                                    .size(50.dp)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                    ) {
                                        nav.navigate(tab.route) {
                                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    painterResource(tab.drawable),
                                    contentDescription = tab.label,
                                    tint = if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(26.dp),
                                )
                                if (selected) {
                                    Box(
                                        Modifier
                                            .align(Alignment.TopEnd)
                                            .size(7.dp)
                                            .background(MaterialTheme.colorScheme.primary, CircleShape),
                                    )
                                }
                            }
                        }
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
                // Plain crossfade: no slide/ghosting between tabs.
                enterTransition = { androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(120)) },
                exitTransition = { androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(90)) },
                popEnterTransition = { androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(120)) },
                popExitTransition = { androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(90)) },
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
