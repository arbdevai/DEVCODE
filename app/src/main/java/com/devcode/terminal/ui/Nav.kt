package com.devcode.terminal.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.devcode.terminal.ui.screens.DashboardScreen
import com.devcode.terminal.ui.screens.SettingsScreen
import com.devcode.terminal.ui.screens.TerminalScreen
import com.devcode.terminal.ui.screens.UbuntuScreen

sealed class Route(val route: String, val label: String) {
    data object Dashboard : Route("dashboard", "Home")
    data object Ubuntu : Route("ubuntu", "Ubuntu")
    data object Terminal : Route("terminal", "Terminal")
    data object Settings : Route("settings", "Settings")

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
            ) {
                Route.items.forEach { route ->
                    NavigationBarItem(
                        selected = currentRoute == route.route,
                        onClick = {
                            navController.navigate(route.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            Icon(
                                imageVector = when (route) {
                                    Route.Dashboard -> Icons.Filled.Home
                                    Route.Ubuntu -> Icons.Filled.Folder
                                    Route.Terminal -> Icons.Filled.Terminal
                                    Route.Settings -> Icons.Filled.Settings
                                },
                                contentDescription = route.label,
                            )
                        },
                        label = { Text(route.label) },
                    )
                }
            }
        },
    ) { paddingValues ->
        NavHost(
            navController = navController,
            startDestination = Route.Dashboard.route,
            modifier = Modifier.fillMaxSize(),
        ) {
            composable(Route.Dashboard.route) {
                DashboardScreen(
                    onOpenTerminal = { navController.navigate(Route.Terminal.route) },
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
