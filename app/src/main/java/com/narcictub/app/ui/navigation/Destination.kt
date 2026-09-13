package com.narcictub.app.ui.navigation

import kotlinx.serialization.Serializable

/**
 * Type-safe navigation destinations. Each object is a route; Navigation 2.8
 * resolves them via kotlinx.serialization — no string route parsing anywhere.
 */
sealed interface Destination {

    @Serializable
    data object Home : Destination

    @Serializable
    data object Downloads : Destination

    @Serializable
    data object History : Destination

    @Serializable
    data object Settings : Destination
}

/** Top-level tabs shown in the bottom bar, in display order. */
val topLevelDestinations = listOf(
    Destination.Home,
    Destination.Downloads,
    Destination.History,
    Destination.Settings,
)
