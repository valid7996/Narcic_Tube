package com.narcictub.app.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.narcictub.app.ui.downloads.DownloadsScreen
import com.narcictub.app.ui.theme.honeycomb
import com.narcictub.app.ui.home.HomeScreen
import com.narcictub.app.ui.playback.PlaybackScreen
import com.narcictub.app.ui.settings.SettingsScreen

/**
 * Root app scaffold: bottom bar + NavHost with type-safe routes.
 */
@Composable
fun NarcicTubApp(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    openDownloadsFirst: Boolean = false,
    onDownloadsOpened: () -> Unit = {},
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val showBottomBar = topLevelDestinations.any { dest ->
        currentDestination?.hasRoute(dest::class) == true
    }

    // One-shot deep link from the share dialog's "Go to downloads" button:
    // land directly on the Downloads tab where live progress is visible.
    LaunchedEffect(openDownloadsFirst) {
        if (openDownloadsFirst) {
            navController.navigateToTopLevel(Destination.Downloads)
            onDownloadsOpened()
        }
    }

    Scaffold(
        modifier = modifier.honeycomb(
            MaterialTheme.colorScheme.primary,
            alpha = 0.05f,
            tile = 80.dp,
        ),
        containerColor = MaterialTheme.colorScheme.background,
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
            composable<Destination.Downloads> {
                // تاریخچه داخل همین صفحه است: پوشه کندو بالا، تاریخچه پایین
                DownloadsScreen(
                    onPlayMedia = { itemId ->
                        navController.navigate(Destination.Playback(itemId))
                    },
                )
            }
            composable<Destination.Playback> {
                PlaybackScreen(onBack = { navController.popBackStack() })
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
