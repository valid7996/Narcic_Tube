package com.narcictub.app.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute

/**
 * Material 3 bottom navigation bar bound to top-level destinations. The
 * selected icon sits on a honey-tinted circular pill and the Downloads tab
 * can carry a live count badge.
 */
@Composable
fun NarcicTubBottomBar(
    destinations: List<TopLevelDestinationUi>,
    currentDestination: NavDestination?,
    onNavigate: (Destination) -> Unit,
    modifier: Modifier = Modifier,
    badgeCount: Int = 0,
) {
    NavigationBar(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        destinations.forEach { entry ->
            val selected = currentDestination?.hasRoute(entry.destination::class) == true
            // Badge only ever shows on Downloads (live active count).
            val badge = if (entry.destination is Destination.Downloads && badgeCount > 0) badgeCount else null
            NavigationBarItem(
                selected = selected,
                onClick = { onNavigate(entry.destination) },
                icon = {
                    BarIcon(
                        selected = selected,
                        selectedIcon = entry.selectedIcon,
                        unselectedIcon = entry.unselectedIcon,
                        badge = badge,
                    )
                },
                label = {
                    Text(
                        text = entry.label,
                        fontSize = 11.sp,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                },
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
    badge: Int?,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(40.dp),
    ) {
        // Honey-tinted circular pill behind the selected icon.
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(
                    if (selected) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                    } else {
                        Color.Transparent
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (selected) selectedIcon else unselectedIcon,
                contentDescription = null,
                tint = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(22.dp),
            )
        }
        if (badge != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 4.dp, y = (-2).dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(horizontal = 5.dp),
            ) {
                Text(
                    text = badge.toString(),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}
