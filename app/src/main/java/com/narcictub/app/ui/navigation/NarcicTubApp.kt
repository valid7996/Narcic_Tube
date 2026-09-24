package com.narcictub.app.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.narcictub.app.ui.downloads.DownloadsScreen
import com.narcictub.app.ui.history.HistoryScreen
import com.narcictub.app.ui.home.HomeScreen
import com.narcictub.app.ui.home.PendingShare
import com.narcictub.app.ui.home.ShareIntakeViewModel
import com.narcictub.app.ui.playback.PlaybackScreen
import com.narcictub.app.ui.settings.SettingsScreen
import com.narcictub.app.ui.share.ShareDownloadScreen

/**
 * Root app scaffold: bottom bar + NavHost with type-safe routes.
 */
@Composable
fun NarcicTubApp(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val showBottomBar = topLevelDestinations.any { dest ->
        currentDestination?.hasRoute(dest::class) == true
    }

    // PHASE 17: an Android-Share intake is processed once, at the nav root.
    // A validated URL opens the dedicated Share download screen ("Download
    // as") where the user picks a format and queues the download; a share
    // with no usable link routes to Home with its safe message. The intake
    // ViewModel here is the same activity-scoped instance the activity and
    // the screens see — single source of truth.
    val shareViewModel: ShareIntakeViewModel = hiltViewModel()
    val pendingShare by shareViewModel.pending.collectAsStateWithLifecycle()
    LaunchedEffect(pendingShare) {
        when (val share = pendingShare) {
            is PendingShare.Url -> {
                shareViewModel.onConsumed()
                navController.navigate(Destination.ShareDownload(share.url))
            }
            is PendingShare.Invalid -> {
                shareViewModel.onConsumed()
                navController.navigateToTopLevel(Destination.Home)
            }
            null -> Unit
        }
    }

    Scaffold(
        modifier = modifier,
        bottomBar = {
            if (showBottomBar) {
                NarcicTubBottomBar(
                    destinations = topLevelDestinationUiList,
                    currentDestination = currentDestination,
                    onNavigate = { dest -> navController.navigateToTopLevel(dest) },
                )
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Destination.Home,
            modifier = Modifier.padding(innerPadding),
            enterTransition = {
                slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(250))
            },
            exitTransition = { fadeOut(tween(200)) },
            popEnterTransition = { fadeIn(tween(200)) },
            popExitTransition = {
                slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(250))
            },
        ) {
            composable<Destination.Home> { HomeScreen() }
            composable<Destination.Downloads> { DownloadsScreen() }
            composable<Destination.History> {
                HistoryScreen(
                    onPlayMedia = { itemId ->
                        navController.navigate(Destination.Playback(itemId))
                    },
                )
            }
            composable<Destination.Playback> {
                PlaybackScreen(onBack = { navController.popBackStack() })
            }
            composable<Destination.ShareDownload> {
                ShareDownloadScreen(
                    onQueued = { navController.navigateToTopLevel(Destination.Downloads) },
                )
            }
            composable<Destination.Settings> { SettingsScreen() }
        }
    }
}

/**
 * Top-level navigation uses save/restore state so tab switches preserve each
 * tab's own back stack and state.
 */
private fun NavHostController.navigateToTopLevel(destination: Destination) {
    navigate(destination) {
        popUpTo(Destination.Home) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
