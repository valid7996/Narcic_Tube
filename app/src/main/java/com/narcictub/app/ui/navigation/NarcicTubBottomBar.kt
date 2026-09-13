package com.narcictub.app.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute

/**
 * Material 3 bottom navigation bar bound to top-level destinations.
 */
@Composable
fun NarcicTubBottomBar(
    destinations: List<TopLevelDestinationUi>,
    currentDestination: NavDestination?,
    onNavigate: (Destination) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationBar(modifier = modifier) {
        destinations.forEach { entry ->
            val selected = currentDestination?.hasRoute(entry.destination::class) == true
            NavigationBarItem(
                selected = selected,
                onClick = { onNavigate(entry.destination) },
                icon = {
                    BarIcon(
                        selected = selected,
                        selectedIcon = entry.selectedIcon,
                        unselectedIcon = entry.unselectedIcon,
                    )
                },
                label = { Text(entry.label) },
                alwaysShowLabel = true,
            )
        }
    }
}

@Composable
private fun BarIcon(
    selected: Boolean,
    selectedIcon: ImageVector,
    unselectedIcon: ImageVector,
) {
    AnimatedVisibility(visible = true) {
        Icon(
            imageVector = if (selected) selectedIcon else unselectedIcon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
        )
    }
}
