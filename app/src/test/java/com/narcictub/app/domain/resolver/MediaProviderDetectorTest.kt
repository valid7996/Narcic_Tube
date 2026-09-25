package com.narcictub.app.domain.resolver

import com.narcictub.app.domain.model.MediaProvider
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PHASE 14-style coverage for PHASE 17 provider detection: host-based,
 * conservative, and careful about lookalike hosts. Detection is NOT support.
 */
class MediaProviderDetectorTest {

    @Test
    fun `youtube com is detected`() {
        assertEquals(MediaProvider.YOUTUBE, MediaProviderDetector.detect("https://youtube.com/watch?v=1"))
    }

    @Test
    fun `www and mobile youtube subdomains are detected`() {
        assertEquals(MediaProvider.YOUTUBE, MediaProviderDetector.detect("https://www.youtube.com/watch?v=1"))
        assertEquals(MediaProvider.YOUTUBE, MediaProviderDetector.detect("https://m.youtube.com/watch?v=1"))
        assertEquals(MediaProvider.YOUTUBE, MediaProviderDetector.detect("https://music.youtube.com/watch?v=1"))
    }

    @Test
    fun `youtu be short links are detected as youtube`() {
        assertEquals(MediaProvider.YOUTUBE, MediaProviderDetector.detect("https://youtu.be/abc123"))
    }

    @Test
    fun `deeper youtube subdomains are detected`() {
        assertEquals(MediaProvider.YOUTUBE, MediaProviderDetector.detect("https://static.youtube.com/x"))
    }

    @Test
    fun `lookalike hosts are not youtube`() {
        assertEquals(MediaProvider.UNKNOWN, MediaProviderDetector.detect("https://notyoutube.com/watch"))
        assertEquals(MediaProvider.UNKNOWN, MediaProviderDetector.detect("https://youtube.com.evil.example.com/watch"))
    }

    @Test
    fun `instagram hosts are detected`() {
        assertEquals(MediaProvider.INSTAGRAM, MediaProviderDetector.detect("https://instagram.com/p/1"))
        assertEquals(MediaProvider.INSTAGRAM, MediaProviderDetector.detect("https://www.instagram.com/p/1"))
        assertEquals(MediaProvider.INSTAGRAM, MediaProviderDetector.detect("https://cdn.instagram.com/p/1"))
    }

    @Test
    fun `unknown hosts and malformed urls fall back to unknown`() {
        assertEquals(MediaProvider.UNKNOWN, MediaProviderDetector.detect("https://cdn.example.com/clip.mp4"))
        assertEquals(MediaProvider.UNKNOWN, MediaProviderDetector.detect("not a url"))
        assertEquals(MediaProvider.UNKNOWN, MediaProviderDetector.detect(""))
    }

    @Test
    fun `scheme case does not affect detection`() {
        assertEquals(MediaProvider.YOUTUBE, MediaProviderDetector.detect("HTTPS://WWW.YOUTUBE.COM/watch?v=1"))
    }
}

/*
 * HONEY — expanded platform recognition: every new provider is detected by
 * its real hosts (incl. shorteners/subdomains) while lookalike hosts stay
 * UNKNOWN.
 */
class MediaProviderExpansionTest {

    @Test
    fun `new platforms are detected by their real hosts`() {
        val cases = mapOf(
            "https://www.tiktok.com/@user/video/123" to MediaProvider.TIKTOK,
            "https://vm.tiktok.com/abcd/" to MediaProvider.TIKTOK,
            "https://x.com/user/status/123" to MediaProvider.TWITTER,
            "https://mobile.twitter.com/user/status/1" to MediaProvider.TWITTER,
            "https://www.facebook.com/watch/?v=1" to MediaProvider.FACEBOOK,
            "https://fb.watch/abcd/" to MediaProvider.FACEBOOK,
            "https://www.pinterest.com/pin/123/" to MediaProvider.PINTEREST,
            "https://pin.it/abcd" to MediaProvider.PINTEREST,
            "https://www.snapchat.com/spotlight/xyz" to MediaProvider.SNAPCHAT,
            "https://soundcloud.com/artist/track" to MediaProvider.SOUNDCLOUD,
            "https://on.soundcloud.com/abcd" to MediaProvider.SOUNDCLOUD,
            "https://open.spotify.com/track/abc" to MediaProvider.SPOTIFY,
        )
        cases.forEach { (url, expected) ->
            assertEquals(url, expected, MediaProviderDetector.detect(url))
        }
    }

    @Test
    fun `lookalike new providers are not recognized`() {
        listOf(
            "https://tiktok.com.evil.com/video/1",
            "https://x.com.evil.example.com/status/1",
            "https://notfacebook.com/watch",
        ).forEach { url ->
            assertEquals(url, MediaProvider.UNKNOWN, MediaProviderDetector.detect(url))
        }
    }
}
