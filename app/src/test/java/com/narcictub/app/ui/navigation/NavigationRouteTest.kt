package com.narcictub.app.ui.navigation

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 14 — type-safe navigation routes rely on kotlinx.serialization for
 * real navigation: Navigation-Compose serializes each CONCRETE route class
 * (never the sealed root, which is intentionally not @Serializable). These
 * tests pin that each route survives the concrete-type round trip, so a
 * broken route annotation would fail here instead of at navigate() time.
 */
class NavigationRouteTest {

    private val json = Json

    @Test
    fun `playback route carries the record id through serialization`() {
        val route = Destination.Playback(itemId = 42L)
        val encoded = json.encodeToString(route)
        assertTrue(encoded.contains("42"))
        val decoded: Destination.Playback = json.decodeFromString(encoded)
        assertEquals(42L, decoded.itemId)
    }

    @Test
    fun `playback route is stable across a round trip with a different id`() {
        val decoded: Destination.Playback =
            json.decodeFromString(json.encodeToString(Destination.Playback(itemId = Long.MAX_VALUE)))
        assertEquals(Long.MAX_VALUE, decoded.itemId)
    }

    @Test
    fun `home route round trips`() {
        val decoded = json.decodeFromString<Destination.Home>(json.encodeToString(Destination.Home))
        assertEquals(Destination.Home, decoded)
    }

    @Test
    fun `history route round trips`() {
        val decoded = json.decodeFromString<Destination.History>(json.encodeToString(Destination.History))
        assertEquals(Destination.History, decoded)
    }

    @Test
    fun `settings route round trips`() {
        val decoded = json.decodeFromString<Destination.Settings>(json.encodeToString(Destination.Settings))
        assertEquals(Destination.Settings, decoded)
    }

    @Test
    fun `downloads route round trips`() {
        val decoded = json.decodeFromString<Destination.Downloads>(json.encodeToString(Destination.Downloads))
        assertEquals(Destination.Downloads, decoded)
    }
}
