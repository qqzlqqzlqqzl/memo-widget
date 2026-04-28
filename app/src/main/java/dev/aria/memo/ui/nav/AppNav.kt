package dev.aria.memo.ui.nav

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.aria.memo.data.ServiceLocator
import dev.aria.memo.ui.SettingsScreen
import dev.aria.memo.ui.SettingsViewModel
import dev.aria.memo.ui.onboarding.OnboardingDialog
import kotlinx.coroutines.launch
import dev.aria.memo.ui.ai.AiChatScreen
import dev.aria.memo.ui.ai.AiChatViewModel
import dev.aria.memo.ui.calendar.CalendarScreen
import dev.aria.memo.ui.calendar.CalendarViewModel
import dev.aria.memo.ui.help.HelpScreen
import dev.aria.memo.ui.notelist.NoteListScreen
import dev.aria.memo.ui.notelist.NoteListViewModel
import dev.aria.memo.ui.tags.TagListScreen
import dev.aria.memo.ui.tags.TagListViewModel

private enum class Tab(val route: String, val label: String) {
    Notes("notes", "笔记"),
    Tags("tags", "标签"),
    Calendar("calendar", "日历"),
    Settings("settings", "设置"),
}

@Composable
fun AppNav(onOpenEditor: () -> Unit) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    // Fixes #144: first-launch onboarding overlay. We read the flag from
    // the OnboardingStore on first composition; the dialog stays mounted
    // until the user either dismisses or finishes the flow, both of
    // which call markCompleted() and flip this to true.
    val onboardingStore = remember { ServiceLocator.onboarding }
    val onboardingCompleted by onboardingStore.completed
        .collectAsStateWithLifecycle(initialValue = true)
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { tab ->
                    val isSelected = currentRoute?.let { r ->
                        backStack?.destination?.hierarchy?.any { it.route == tab.route } == true || r == tab.route
                    } ?: (tab == Tab.Notes)
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = {
                            nav.navigate(tab.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            // P8 Fix-7 #6 原计划"选中=Filled / 未选=Outlined"（M3 惯例），
                            // 但当前 Compose BOM 2024.09 下 Icons.Outlined.X 的 receiver
                            // 契约破坏性变更导致编译失败。降级为全 Filled；
                            // 选中态靠 NavigationBar 自带 pill indicator 区分。
                            // P8.1 升级 Compose BOM 到 2026.03+ 后再恢复 Outlined 切换。
                            Icon(
                                imageVector = when (tab) {
                                    Tab.Notes -> Icons.AutoMirrored.Filled.Notes
                                    Tab.Tags -> Icons.AutoMirrored.Filled.Label
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
        // P8 Agent-6 W-lint: 把 150ms fade 动画从外层 AnimatedContent wrapper 下沉到
        // NavHost 的 enterTransition/exitTransition。原来 AnimatedContent 的 _ 参数
        // 不用会被 lint 误报 UnusedContentLambdaTargetStateParameter。
        // 语义不变 — tab 切换仍然是 150ms 淡入淡出（M3 Expressive "subtle" token）。
        NavHost(
            navController = nav,
            startDestination = Tab.Notes.route,
            modifier = Modifier.fillMaxSize(),
            enterTransition = { fadeIn(tween(150)) },
            exitTransition = { fadeOut(tween(150)) },
            popEnterTransition = { fadeIn(tween(150)) },
            popExitTransition = { fadeOut(tween(150)) },
        ) {
                composable(Tab.Notes.route) {
                    val vm: NoteListViewModel = viewModel(factory = NoteListViewModel.Factory)
                    NoteListScreen(
                        viewModel = vm,
                        onOpenEditor = onOpenEditor,
                        // Fixes #71 (P7.0.1): forward per-note uid so the
                        // long-press "问 AI" menu pins the body as context.
                        onOpenAiChat = { noteUid ->
                            nav.navigate(
                                if (noteUid != null) "ai_chat?noteUid=$noteUid"
                                else "ai_chat"
                            )
                        },
                        modifier = Modifier.padding(padding),
                    )
                }
                composable(Tab.Tags.route) {
                    val vm: TagListViewModel = viewModel(factory = TagListViewModel.Factory)
                    TagListScreen(
                        viewModel = vm,
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
                        onOpenHelp = {
                            // Sub-page off the settings tab, not a bottom-nav destination.
                            // singleTop avoids stacking duplicate help screens on repeat taps.
                            nav.navigate("help") { launchSingleTop = true }
                        },
                        modifier = Modifier.padding(padding),
                    )
                }
                composable("help") {
                    HelpScreen(
                        onBack = { nav.popBackStack() },
                        modifier = Modifier.padding(padding),
                    )
                }
                composable(
                    route = "ai_chat?noteUid={noteUid}",
                    arguments = listOf(
                        navArgument("noteUid") {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                    ),
                ) { backStackEntry ->
                    val noteUid = backStackEntry.arguments?.getString("noteUid")
                    val vm: AiChatViewModel = viewModel(
                        factory = AiChatViewModel.factoryFor(noteUid),
                    )
                    AiChatScreen(
                        viewModel = vm,
                        onBack = { nav.popBackStack() },
                        modifier = Modifier.padding(padding),
                    )
                }
        }
    }

    // Fixes #144: render the dialog last so it sits over the navigation
    // shell. The store's flow seeds with `true` (initialValue) so the
    // dialog only appears for users we *know* haven't completed yet —
    // never as a flicker on every launch.
    if (!onboardingCompleted) {
        OnboardingDialog(
            onGoToSettings = {
                coroutineScope.launch { onboardingStore.markCompleted() }
                nav.navigate(Tab.Settings.route) {
                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            },
            onSkip = {
                coroutineScope.launch { onboardingStore.markCompleted() }
            },
        )
    }
}
