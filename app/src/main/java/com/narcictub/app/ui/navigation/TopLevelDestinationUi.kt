package com.narcictub.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Display metadata for a top-level destination: label + selected/unselected
 * icons (Material filled/outlined pairs). History lives INSIDE the
 * Downloads screen (hive folder up, history down) — only three tabs.
 */
data class TopLevelDestinationUi(
    val destination: Destination,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)

val topLevelDestinationUiList: List<TopLevelDestinationUi> = listOf(
    TopLevelDestinationUi(
        destination = Destination.Home,
        label = "Home",
        selectedIcon = Icons.Filled.Home,
        unselectedIcon = Icons.Outlined.Home,
    ),
    TopLevelDestinationUi(
        destination = Destination.Downloads,
        label = "Downloads",
        selectedIcon = Icons.Filled.Download,
        unselectedIcon = Icons.Outlined.Download,
    ),
    TopLevelDestinationUi(
        destination = Destination.Settings,
        label = "Settings",
        selectedIcon = Icons.Filled.Settings,
        unselectedIcon = Icons.Outlined.Settings,
    ),
)
