package com.narcictub.app.domain.resolver

import com.narcictub.app.domain.model.MediaProvider
import java.net.URI

/**
 * PHASE 17: conservative, host-based provider detection for shared/pasted
 * URLs. Pure, never throws, never performs network work.
 *
 * Detection is NOT support: a recognized provider still requires an
 * explicitly implemented [MediaExtractor] before any extraction happens.
 */
object MediaProviderDetector {

    private val YOUTUBE_HOSTS = setOf(
        "youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be",
    )
    private val INSTAGRAM_HOSTS = setOf(
        "instagram.com", "www.instagram.com", "m.instagram.com",
    )

    fun detect(url: String): MediaProvider {
        val host = try {
            URI(url).host?.lowercase() ?: return MediaProvider.UNKNOWN
        } catch (_: Exception) {
            return MediaProvider.UNKNOWN
        }
        return when {
            host in YOUTUBE_HOSTS || host.endsWith(".youtube.com") -> MediaProvider.YOUTUBE
            host in INSTAGRAM_HOSTS || host.endsWith(".instagram.com") -> MediaProvider.INSTAGRAM
            else -> MediaProvider.UNKNOWN
        }
    }
}
