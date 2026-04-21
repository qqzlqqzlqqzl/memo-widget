package dev.aria.memo.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.aria.memo.ui.SettingsScreen
import dev.aria.memo.ui.SettingsViewModel
import dev.aria.memo.ui.calendar.CalendarScreen
import dev.aria.memo.ui.calendar.CalendarViewModel
import dev.aria.memo.ui.notelist.NoteListScreen
import dev.aria.memo.ui.notelist.NoteListViewModel

private enum class Tab(val route: String, val label: String) {
    Notes("notes", "笔记"),
    Calendar("calendar", "日历"),
    Settings("settings", "设置"),
}

@Composable
fun AppNav(onOpenEditor: () -> Unit) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = currentRoute?.let { r ->
                            backStack?.destination?.hierarchy?.any { it.route == tab.route } == true || r == tab.route
                        } ?: (tab == Tab.Notes),
                        onClick = {
                            nav.navigate(tab.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            Icon(
                                imageVector = when (tab) {
                                    Tab.Notes -> Icons.AutoMirrored.Filled.Notes
                                    Tab.Calendar -> Icons.Filled.CalendarMonth
                                    Tab.Settings -> Icons.Filled.Settings
                                },
                                contentDescription = null,
                            )
                        },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = Tab.Notes.route,
            modifier = Modifier.fillMaxSize(),
        ) {
            composable(Tab.Notes.route) {
                val vm: NoteListViewModel = viewModel(factory = NoteListViewModel.Factory)
                NoteListScreen(
                    viewModel = vm,
                    onOpenEditor = onOpenEditor,
                    modifier = Modifier.padding(padding),
                )
            }
            composable(Tab.Calendar.route) {
                val vm: CalendarViewModel = viewModel(factory = CalendarViewModel.Factory)
                CalendarScreen(
                    viewModel = vm,
                    modifier = Modifier.padding(padding),
                )
            }
            composable(Tab.Settings.route) {
                val vm: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory)
                SettingsScreen(
                    viewModel = vm,
                    onOpenEditor = onOpenEditor,
                    modifier = Modifier.padding(padding),
                )
            }
        }
    }
}

