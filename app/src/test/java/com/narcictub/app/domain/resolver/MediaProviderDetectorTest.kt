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
