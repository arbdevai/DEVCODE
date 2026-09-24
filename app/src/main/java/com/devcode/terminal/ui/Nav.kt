package com.devcode.terminal.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.devcode.terminal.ui.screens.DashboardScreen
import com.devcode.terminal.ui.screens.SettingsScreen
import com.devcode.terminal.ui.screens.TerminalScreen
import com.devcode.terminal.ui.screens.UbuntuScreen

sealed class Route(
    val route: String,
    val label: String,
    val selectedIcon: androidx.compose.ui.graphics.vector.ImageVector,
    val unselectedIcon: androidx.compose.ui.graphics.vector.ImageVector
) {
    data object Dashboard : Route("dashboard", "Dashboard", Icons.Filled.Home, Icons.Outlined.Home)
    data object Ubuntu : Route("ubuntu", "Ubuntu", Icons.Filled.Folder, Icons.Outlined.Folder)
    data object Terminal : Route("terminal", "Terminal", Icons.Filled.Terminal, Icons.Outlined.Terminal)
    data object Settings : Route("settings", "Settings", Icons.Filled.Settings, Icons.Outlined.Settings)

    companion object {
        val items = listOf(Dashboard, Ubuntu, Terminal, Settings)
    }
}

@Composable
fun AppNav(modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    val backStackEntry = navController.currentBackStackEntryAsState().value
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
            ) {
                Route.items.forEach { route ->
                    val isSelected = currentRoute == route.route
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = {
                            if (currentRoute != route.route) {
                                navController.navigate(route.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        icon = {
                            Icon(
                                imageVector = if (isSelected) route.selectedIcon else route.unselectedIcon,
                                contentDescription = route.label,
                            )
                        },
                        label = {
                            Text(
                                text = route.label,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    )
                }
            }
        },
    ) { paddingValues ->
        NavHost(
            navController = navController,
            startDestination = Route.Dashboard.route,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            composable(Route.Dashboard.route) {
                DashboardScreen(
                    onOpenTerminal = { navController.navigate(Route.Terminal.route) },
                    onOpenUbuntu = { navController.navigate(Route.Ubuntu.route) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            composable(Route.Ubuntu.route) {
                UbuntuScreen(modifier = Modifier.fillMaxSize())
            }
            composable(Route.Terminal.route) {
                TerminalScreen(modifier = Modifier.fillMaxSize())
            }
            composable(Route.Settings.route) {
                SettingsScreen(modifier = Modifier.fillMaxSize())
            }
        }
    }
}
