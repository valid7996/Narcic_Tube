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

    /**
     * PHASE 11: in-app playback of ONE completed, available local media
     * record. Not a top-level tab — the bottom bar hides on this route.
     * The argument is only the record id; the screen's ViewModel resolves
     * the validated URI itself, never trusting navigation input.
     */
    @Serializable
    data class Playback(val itemId: Long) : Destination

    @Serializable
    data object Settings : Destination
}

/** Top-level tabs shown in the bottom bar, in display order. History
 * lives inside the Downloads screen (hive folder up, history down). */
val topLevelDestinations = listOf(
    Destination.Home,
    Destination.Downloads,
    Destination.Settings,
)
