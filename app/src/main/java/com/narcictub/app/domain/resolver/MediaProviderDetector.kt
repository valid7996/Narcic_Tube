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
    private val TIKTOK_HOSTS = setOf("tiktok.com")
    private val TWITTER_HOSTS = setOf("twitter.com", "x.com")
    private val FACEBOOK_HOSTS = setOf("facebook.com", "fb.com", "fb.watch")
    private val PINTEREST_HOSTS = setOf("pinterest.com", "pin.it")
    private val SNAPCHAT_HOSTS = setOf("snapchat.com")
    private val SOUNDCLOUD_HOSTS = setOf("soundcloud.com")
    private val SPOTIFY_HOSTS = setOf("spotify.com")

    fun detect(url: String): MediaProvider {
        val host = try {
            URI(url).host?.lowercase() ?: return MediaProvider.UNKNOWN
        } catch (_: Exception) {
            return MediaProvider.UNKNOWN
        }
        return when {
            host in YOUTUBE_HOSTS || host.endsWith(".youtube.com") -> MediaProvider.YOUTUBE
            host in INSTAGRAM_HOSTS || host.endsWith(".instagram.com") -> MediaProvider.INSTAGRAM
            host in TIKTOK_HOSTS || host.endsWith(".tiktok.com") -> MediaProvider.TIKTOK
            host in TWITTER_HOSTS || host.endsWith(".twitter.com") || host.endsWith(".x.com") -> MediaProvider.TWITTER
            host in FACEBOOK_HOSTS || host.endsWith(".facebook.com") || host.endsWith(".fb.com") -> MediaProvider.FACEBOOK
            host in PINTEREST_HOSTS || host.endsWith(".pinterest.com") -> MediaProvider.PINTEREST
            host in SNAPCHAT_HOSTS || host.endsWith(".snapchat.com") -> MediaProvider.SNAPCHAT
            host in SOUNDCLOUD_HOSTS || host.endsWith(".soundcloud.com") -> MediaProvider.SOUNDCLOUD
            host in SPOTIFY_HOSTS || host.endsWith(".spotify.com") -> MediaProvider.SPOTIFY
            else -> MediaProvider.UNKNOWN
        }
    }
}
