package com.stepcast.app.ui

import android.os.Bundle
import androidx.compose.ui.res.stringResource
import com.stepcast.app.R
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavType
import com.stepcast.app.StepcastApplication
import com.stepcast.app.ui.screens.CategoryScreen
import com.stepcast.app.ui.screens.FullPlayerSheet
import com.stepcast.app.ui.screens.HistoryScreen
import com.stepcast.app.ui.screens.HomeScreen
import com.stepcast.app.ui.screens.PlayerBar
import com.stepcast.app.ui.screens.PodcastScreen
import com.stepcast.app.ui.screens.QueueScreen
import com.stepcast.app.ui.screens.SearchScreen
import com.stepcast.app.ui.screens.SettingsScreen
import com.stepcast.app.ui.screens.SmartPlayEditorScreen
import com.stepcast.app.ui.theme.StepcastTheme

class MainActivity : ComponentActivity() {

    private lateinit var playerConnection: PlayerConnection

    // feed URL arriving via share sheet or a podcast:// style link
    private val sharedFeedUrl = androidx.compose.runtime.mutableStateOf<String?>(null)

    private fun extractFeedUrl(intent: android.content.Intent?): String? {
        intent ?: return null
        return when (intent.action) {
            android.content.Intent.ACTION_SEND -> FeedUrlIntents.firstUrlIn(
                intent.getStringExtra(android.content.Intent.EXTRA_TEXT)
            )
            android.content.Intent.ACTION_VIEW ->
                intent.data?.toString()?.let(FeedUrlIntents::normalizeScheme)
            else -> null
        }
    }

    /** A pinned SmartPlay shortcut fires straight into playback. */
    private fun handleSmartPlayShortcut(intent: android.content.Intent?) {
        if (intent?.action != com.stepcast.app.SmartPlayShortcuts.ACTION_START) return
        val name = intent.getStringExtra("smartplay") ?: return
        sendBroadcast(
            android.content.Intent(
                this, com.stepcast.app.playback.CommandReceiver::class.java
            )
                .setAction(
                    com.stepcast.app.playback.CommandReceiver.ACTION_START_SMART_PLAY
                )
                .putExtra("smartplay", name)
        )
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        extractFeedUrl(intent)?.let { sharedFeedUrl.value = it }
        handleSmartPlayShortcut(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        extractFeedUrl(intent)?.let { sharedFeedUrl.value = it }
        handleSmartPlayShortcut(intent)
        com.stepcast.app.ui.theme.ThemePrefs.init(this)
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            registerForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
            ) { }.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        playerConnection = PlayerConnection(this, lifecycleScope)
        setContent {
            // system bars must follow the APP theme, not the OS theme —
            // otherwise status-bar icons stay dark on our dark background
            // when the user forces dark mode on a light-mode system
            val darkTheme = when (com.stepcast.app.ui.theme.ThemePrefs.mode) {
                com.stepcast.app.ui.theme.ThemeMode.DARK -> true
                com.stepcast.app.ui.theme.ThemeMode.LIGHT -> false
                else -> androidx.compose.foundation.isSystemInDarkTheme()
            }
            androidx.compose.runtime.DisposableEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = androidx.activity.SystemBarStyle.auto(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT
                    ) { darkTheme },
                    navigationBarStyle = androidx.activity.SystemBarStyle.auto(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT
                    ) { darkTheme }
                )
                onDispose {}
            }
            StepcastTheme {
                StepcastApp(playerConnection, sharedFeedUrl.value)
            }
        }
    }

    override fun onDestroy() {
        playerConnection.release()
        super.onDestroy()
    }
}

@Composable
fun StepcastApp(player: PlayerConnection, sharedFeedUrl: String? = null) {
    val navController = rememberNavController()
    val app = LocalContext.current.applicationContext as StepcastApplication
    val playerState by player.state.collectAsState()
    val queue by app.repository.queue.collectAsState(initial = emptyList())
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    var playerExpanded by remember { mutableStateOf(false) }

    // a shared/linked feed URL lands in Discover, prefilled
    androidx.compose.runtime.LaunchedEffect(sharedFeedUrl) {
        if (!sharedFeedUrl.isNullOrBlank()) {
            navController.navigate("search") { launchSingleTop = true }
        }
    }

    Scaffold(
        bottomBar = {
            Column {
                // the pill hides while the full player is open — its
                // transport lives on the player itself
                if (playerState.episodeId != null && !playerExpanded) {
                    PlayerBar(
                        state = playerState,
                        player = player,
                        onExpand = { playerExpanded = true }
                    )
                }
                NavigationBar {
                    val navColors = NavigationBarItemDefaults.colors(
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    // child routes highlight the tab they were opened from, so
                    // the bottom bar always shows where you are
                    val queueOwned = currentRoute == "queue" ||
                        currentRoute == "downloads" ||
                        currentRoute == "history" ||
                        currentRoute?.startsWith("smartplay/") == true
                    // save/restore instead of destroy/recreate, so each tab
                    // keeps its scroll position across switches; tapping the
                    // tab you're already on returns to that tab's root
                    fun goToTab(route: String) {
                        // nav taps land UNDER the player overlay — collapse it
                        playerExpanded = false
                        if (currentRoute == route) return
                        // deeper inside this tab's stack? pop back to its root
                        if (navController.popBackStack(route, inclusive = false)) {
                            return
                        }
                        navController.navigate(route) {
                            popUpTo(navController.graph.startDestinationId) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                    NavigationBarItem(
                        selected = currentRoute != null && !queueOwned,
                        onClick = { goToTab("home") },
                        icon = { Icon(Icons.Rounded.Home, contentDescription = stringResource(R.string.library)) },
                        colors = navColors
                    )
                    NavigationBarItem(
                        selected = queueOwned,
                        onClick = { goToTab("queue") },
                        icon = {
                            BadgedBox(badge = {
                                if (queue.isNotEmpty()) {
                                    Badge { Text(queue.size.toString()) }
                                }
                            }) {
                                Icon(
                                    Icons.AutoMirrored.Rounded.QueueMusic,
                                    contentDescription = stringResource(R.string.up_next)
                                )
                            }
                        },
                        colors = navColors
                    )
                }
            }
        }
    ) { padding ->
        androidx.compose.foundation.layout.Box(Modifier.padding(padding)) {
        NavHost(
            navController = navController,
            startDestination = "home",
            enterTransition = {
                androidx.compose.animation.fadeIn(
                    androidx.compose.animation.core.tween(220)
                ) + androidx.compose.animation.slideInVertically(
                    androidx.compose.animation.core.tween(220)
                ) { it / 24 }
            },
            exitTransition = {
                androidx.compose.animation.fadeOut(
                    androidx.compose.animation.core.tween(150)
                )
            },
            popEnterTransition = {
                androidx.compose.animation.fadeIn(
                    androidx.compose.animation.core.tween(220)
                )
            },
            popExitTransition = {
                androidx.compose.animation.fadeOut(
                    androidx.compose.animation.core.tween(150)
                ) + androidx.compose.animation.slideOutVertically(
                    androidx.compose.animation.core.tween(220)
                ) { it / 24 }
            }
        ) {
            composable("home") {
                HomeScreen(
                    repository = app.repository,
                    onPodcastClick = { navController.navigate("podcast/$it") },
                    onCategoryClick = { name ->
                        navController.navigate("category/${android.net.Uri.encode(name)}")
                    },
                    onOpenSettings = {
                        navController.navigate("settings") { launchSingleTop = true }
                    },
                    onOpenDiscover = {
                        navController.navigate("search") { launchSingleTop = true }
                    },
                    onOpenSearch = {
                        navController.navigate("librarysearch") { launchSingleTop = true }
                    },
                    onOpenInbox = {
                        navController.navigate("inbox") { launchSingleTop = true }
                    }
                )
            }
            composable("inbox") {
                com.stepcast.app.ui.screens.InboxScreen(
                    repository = app.repository,
                    player = player,
                    playerState = playerState
                )
            }
            composable("settings") { SettingsScreen(repository = app.repository) }
            composable("librarysearch") {
                com.stepcast.app.ui.screens.LibrarySearchScreen(
                    repository = app.repository,
                    player = player,
                    playerState = playerState,
                    onPodcastClick = { navController.navigate("podcast/$it") }
                )
            }
            composable(
                route = "category/{name}",
                arguments = listOf(navArgument("name") { type = NavType.StringType })
            ) { entry ->
                val name = entry.arguments?.getString("name") ?: return@composable
                CategoryScreen(
                    category = name,
                    repository = app.repository,
                    player = player,
                    playerState = playerState,
                    onPodcastClick = { navController.navigate("podcast/$it") },
                    onRenamed = { newName ->
                        navController.popBackStack()
                        navController.navigate(
                            "category/${android.net.Uri.encode(newName)}"
                        )
                    },
                    onDeleted = { navController.popBackStack() }
                )
            }
            composable("queue") {
                QueueScreen(
                    repository = app.repository,
                    player = player,
                    onEditSmartPlay = { navController.navigate("smartplay/$it") },
                    onOpenDownloads = {
                        navController.navigate("downloads") { launchSingleTop = true }
                    },
                    onOpenHistory = {
                        navController.navigate("history") { launchSingleTop = true }
                    },
                    onPodcastClick = { navController.navigate("podcast/$it") }
                )
            }
            composable("downloads") {
                com.stepcast.app.ui.screens.DownloadsScreen(
                    repository = app.repository
                )
            }
            composable("history") {
                HistoryScreen(repository = app.repository, player = player)
            }
            composable(
                route = "smartplay/{smartPlayId}",
                arguments = listOf(navArgument("smartPlayId") { type = NavType.LongType })
            ) { entry ->
                val smartPlayId = entry.arguments?.getLong("smartPlayId") ?: return@composable
                SmartPlayEditorScreen(
                    smartPlayId = smartPlayId,
                    repository = app.repository,
                    onDone = { navController.popBackStack() }
                )
            }
            composable("search") {
                SearchScreen(
                    search = app.search,
                    initialQuery = sharedFeedUrl.orEmpty(),
                    onOpenPreview = { feedUrl ->
                        navController.navigate(
                            "preview/${android.net.Uri.encode(feedUrl)}"
                        ) { launchSingleTop = true }
                    }
                )
            }
            composable(
                route = "preview/{feedUrl}",
                arguments = listOf(navArgument("feedUrl") { type = NavType.StringType })
            ) { entry ->
                val feedUrl = entry.arguments?.getString("feedUrl") ?: return@composable
                com.stepcast.app.ui.screens.PodcastPreviewScreen(
                    feedUrl = feedUrl,
                    repository = app.repository,
                    player = player,
                    onSubscribed = {
                        // land back in the Library, where the new show now lives
                        navController.navigate("home") {
                            popUpTo("home") { inclusive = true }
                        }
                    },
                    onOpenPodcast = { navController.navigate("podcast/$it") }
                )
            }
            composable(
                route = "podcast/{podcastId}",
                arguments = listOf(navArgument("podcastId") { type = NavType.LongType })
            ) { entry ->
                val podcastId = entry.arguments?.getLong("podcastId") ?: return@composable
                PodcastScreen(
                    podcastId = podcastId,
                    repository = app.repository,
                    search = app.search,
                    player = player,
                    playerState = playerState,
                    onUnsubscribed = { navController.popBackStack() }
                )
            }
        }
        // full player as an overlay INSIDE the scaffold content, so the
        // bottom nav bar stays visible and tappable while it's open
        androidx.compose.animation.AnimatedVisibility(
            visible = playerExpanded && playerState.episodeId != null,
            enter = androidx.compose.animation.slideInVertically(
                androidx.compose.animation.core.tween(240)
            ) { it },
            exit = androidx.compose.animation.slideOutVertically(
                androidx.compose.animation.core.tween(200)
            ) { it }
        ) {
            FullPlayerSheet(
                state = playerState,
                player = player,
                repository = app.repository,
                onOpenPodcast = { podcastId ->
                    playerExpanded = false
                    navController.navigate("podcast/$podcastId")
                },
                onDismiss = { playerExpanded = false }
            )
        }
        }
    }
}
