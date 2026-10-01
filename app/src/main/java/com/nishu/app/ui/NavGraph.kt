package com.nishu.app.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.nishu.app.DeepLinks
import androidx.compose.ui.Modifier
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.nishu.app.ui.bench.BenchmarkRoute
import com.nishu.app.ui.bench.DiagnosticsRoute
import com.nishu.app.ui.components.BottomTab
import com.nishu.app.ui.components.NishuBottomBar
import com.nishu.app.ui.conversation.ConversationDetailRoute
import com.nishu.app.ui.conversations.ConversationsRoute
import com.nishu.app.ui.home.HomeRoute
import com.nishu.app.ui.memory.MemoryRoute
import com.nishu.app.ui.processing.ProcessingRoute
import com.nishu.app.ui.recording.RecordingRoute
import com.nishu.app.ui.search.SearchRoute
import com.nishu.app.ui.settings.SettingsRoute
import com.nishu.app.ui.transcript.TranscriptRoute

object Routes {
    const val HOME = "home"
    const val CONVERSATIONS = "conversations"
    const val CONVERSATION = "conversation/{id}"
    const val RECORDING = "recording"
    const val PROCESSING = "processing/{id}"
    const val TRANSCRIPT = "transcript/{id}"
    const val MEMORY = "memory"
    const val SEARCH = "search"
    const val SETTINGS = "settings"
    const val BENCHMARK = "benchmark"
    const val DIAGNOSTICS = "diagnostics"

    fun conversation(id: Long) = "conversation/$id"
    fun processing(id: Long) = "processing/$id"
    fun transcript(id: Long) = "transcript/$id"
}

private val tabs = listOf(
    BottomTab(Routes.HOME, "Home", Icons.Rounded.Home),
    BottomTab(Routes.CONVERSATIONS, "Conversations", Icons.Rounded.Forum),
    BottomTab(Routes.MEMORY, "Memory", Icons.Rounded.Shield),
    BottomTab(Routes.SETTINGS, "Settings", Icons.Rounded.Settings),
)

private val idArg = listOf(navArgument("id") { type = NavType.LongType })

private fun enter(): AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    fadeIn(tween(220)) + slideInHorizontally(tween(220)) { it / 12 }
}
private fun exit(): AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = { fadeOut(tween(160)) }
private fun popEnter(): AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    fadeIn(tween(220)) + slideInHorizontally(tween(220)) { -it / 12 }
}
private fun popExit(): AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    fadeOut(tween(160)) + slideOutHorizontally(tween(220)) { it / 12 }
}

private fun NavHostController.switchTab(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

@Composable
fun NishuApp() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val showBar = route in tabs.map { it.route }

    val pending by DeepLinks.pendingConversation.collectAsState()
    LaunchedEffect(pending) {
        pending?.let {
            nav.navigate(Routes.conversation(it)) { launchSingleTop = true }
            DeepLinks.pendingConversation.value = null
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = { if (showBar) NishuBottomBar(tabs, route) { nav.switchTab(it) } },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(padding).fillMaxSize(),
            enterTransition = enter(), exitTransition = exit(), popEnterTransition = popEnter(), popExitTransition = popExit(),
        ) {
            composable(Routes.HOME) {
                HomeRoute(
                    onStartRecording = { nav.navigate(Routes.RECORDING) { launchSingleTop = true } },
                    onOpenConversation = { nav.navigate(Routes.conversation(it)) },
                    onSeeAll = { nav.switchTab(Routes.CONVERSATIONS) },
                    onOpenSettings = { nav.switchTab(Routes.SETTINGS) },
                )
            }
            composable(Routes.CONVERSATIONS) {
                ConversationsRoute(onOpen = { nav.navigate(Routes.conversation(it)) }, onSearch = { nav.navigate(Routes.SEARCH) })
            }
            composable(Routes.MEMORY) { MemoryRoute(onSearch = { nav.navigate(Routes.SEARCH) }) }
            composable(Routes.SETTINGS) {
                SettingsRoute(onBenchmark = { nav.navigate(Routes.BENCHMARK) }, onDiagnostics = { nav.navigate(Routes.DIAGNOSTICS) })
            }
            composable(Routes.CONVERSATION, idArg) { e ->
                ConversationDetailRoute(
                    id = e.arguments!!.getLong("id"),
                    onBack = { nav.popBackStack() },
                    onTranscript = { nav.navigate(Routes.transcript(it)) },
                    onProcessing = { nav.navigate(Routes.processing(it)) },
                )
            }
            composable(Routes.TRANSCRIPT, idArg) { e ->
                TranscriptRoute(id = e.arguments!!.getLong("id"), onBack = { nav.popBackStack() })
            }
            composable(Routes.RECORDING) {
                RecordingRoute(
                    onBack = { nav.popBackStack() },
                    onProcessing = { id -> nav.navigate(Routes.processing(id)) { popUpTo(Routes.HOME) } },
                )
            }
            composable(Routes.PROCESSING, idArg) { e ->
                ProcessingRoute(
                    id = e.arguments!!.getLong("id"),
                    onBack = { nav.popBackStack() },
                    onComplete = { id -> nav.navigate(Routes.conversation(id)) { popUpTo(Routes.HOME) } },
                    onViewTranscript = { id -> nav.navigate(Routes.transcript(id)) },
                )
            }
            composable(Routes.SEARCH) {
                SearchRoute(
                    onBack = { nav.popBackStack() },
                    onOpenConversation = { nav.navigate(Routes.conversation(it)) },
                    onOpenMemory = { nav.switchTab(Routes.MEMORY) },
                )
            }
            composable(Routes.BENCHMARK) { BenchmarkRoute(onBack = { nav.popBackStack() }) }
            composable(Routes.DIAGNOSTICS) { DiagnosticsRoute(onBack = { nav.popBackStack() }) }
        }
    }
}
