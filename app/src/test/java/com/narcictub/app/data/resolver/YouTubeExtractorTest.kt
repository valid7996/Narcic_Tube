package com.narcictub.app.data.resolver

import com.narcictub.app.domain.model.MediaProvider
import com.narcictub.app.domain.resolver.MediaResolveException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 24 — YouTube intake tests: strict independent host validation,
 * content classification (watch/shorts/youtu.be), and honest typed
 * failures backed by the documented BLOCKED feasibility verdict. The
 * production class injects no network dependencies (structurally incapable
 * of requests).
 */
class YouTubeExtractorTest {

    private val extractor = YouTubeExtractor()

    // ===== host validation: accepted =====

    @Test
    fun `youtube com watch urls are claimed`() {
        assertTrue(extractor.supports("https://youtube.com/watch?v=abc123"))
        assertEquals("youtube.com", extractor.youTubeHostOf("https://youtube.com/watch?v=abc123"))
    }

    @Test
    fun `www and music subdomains are claimed`() {
        assertTrue(extractor.supports("https://www.youtube.com/watch?v=abc123"))
        assertTrue(extractor.supports("https://music.youtube.com/watch?v=abc123"))
        assertEquals("www.youtube.com", extractor.youTubeHostOf("https://www.youtube.com/watch?v=abc123"))
    }

    @Test
    fun `youtu be short links are claimed`() {
        assertTrue(extractor.supports("https://youtu.be/abc123"))
        assertEquals("youtu.be", extractor.youTubeHostOf("https://youtu.be/abc123"))
    }

    @Test
    fun `deeper genuine subdomains are claimed`() {
        assertTrue(extractor.supports("https://static.youtube.com/x"))
    }

    // ===== host validation: rejected =====

    @Test
    fun `lookalike hosts are never claimed`() {
        assertFalse(extractor.supports("https://youtube.com.evil.com/watch?v=abc123"))
        assertFalse(extractor.supports("https://notyoutube.com/watch?v=abc123"))
        assertNull(extractor.youTubeHostOf("https://youtube.com.evil.com/watch?v=abc123"))
    }

    @Test
    fun `userinfo urls are rejected`() {
        assertFalse(extractor.supports("https://user:pass@youtube.com/watch?v=abc123"))
        assertNull(extractor.youTubeHostOf("https://user:pass@youtube.com/watch?v=abc123"))
    }

    @Test
    fun `dangerous schemes are rejected`() {
        for (url in listOf(
            "file://youtube.com/watch?v=abc123",
            "javascript:youtube.com/watch",
            "data:text/html,youtube.com",
            "ftp://youtube.com/watch?v=abc123",
        )) {
            assertFalse("must reject $url", extractor.supports(url))
            assertNull(extractor.youTubeHostOf(url))
        }
    }

    @Test
    fun `ip literals and localhost are never claimed`() {
        assertFalse(extractor.supports("http://127.0.0.1/watch?v=abc123"))
        assertFalse(extractor.supports("http://localhost/watch?v=abc123"))
        assertFalse(extractor.supports("http://[::1]/watch?v=abc123"))
    }

    @Test
    fun `malformed urls are rejected without throwing`() {
        for (url in listOf("", "   ", "not a url", "https://")) {
            assertFalse(extractor.supports(url))
            assertNull(extractor.youTubeHostOf(url))
        }
    }

    // ===== routing sanity =====

    @Test
    fun `instagram and unknown hosts are never claimed`() {
        assertFalse(extractor.supports("https://instagram.com/p/Cabc123/"))
        assertFalse(extractor.supports("https://cdn.example.com/clip.mp4"))
    }

    // ===== extraction: honest typed failures =====

    @Test
    fun `watch url routes to ExtractionUnavailable with YouTube provider`() = runBlocking {
        val result = extractor.extract("https://www.youtube.com/watch?v=dQw4w9WgXcQ")

        val error = result.exceptionOrNull()
        assertTrue(
            "expected ExtractionUnavailable, got $error",
            error is MediaResolveException.ExtractionUnavailable,
        )
        assertEquals(MediaProvider.YOUTUBE, (error as MediaResolveException.ExtractionUnavailable).provider)
        assertNull(result.getOrNull())
    }

    @Test
    fun `shorts and youtu be shapes route to ExtractionUnavailable`() = runBlocking {
        for (url in listOf(
            "https://youtube.com/shorts/dQw4w9WgXcQ",
            "https://youtu.be/dQw4w9WgXcQ",
        )) {
            val error = extractor.extract(url).exceptionOrNull()
            assertTrue(
                "expected ExtractionUnavailable for $url",
                error is MediaResolveException.ExtractionUnavailable,
            )
        }
    }

    @Test
    fun `non-content youtube urls are UnsupportedSource`() = runBlocking {
        for (url in listOf(
            "https://youtube.com/", // root
            "https://youtube.com/feed/subscriptions",
            "https://youtube.com/channel/UC123",
            "https://youtube.com/playlist?list=PL123",
            "https://youtu.be/", // short host without id
            "https://youtube.com/watch", // no video id
            "https://youtube.com/watch?list=PL123", // query without v
        )) {
            val error = extractor.extract(url).exceptionOrNull()
            assertTrue(
                "expected UnsupportedSource for $url, got $error",
                error is MediaResolveException.UnsupportedSource,
            )
        }
    }

    @Test
    fun `watch without a valid video id is not recognized as media`() = runBlocking {
        for (query in listOf("v=", "v=%20", "v=..%2F..")) {
            val error = extractor.extract("https://youtube.com/watch?$query").exceptionOrNull()
            assertTrue(
                "expected UnsupportedSource for query $query, got $error",
                error is MediaResolveException.UnsupportedSource,
            )
        }
    }

    @Test
    fun `extract never fabricates metadata`() = runBlocking {
        val result = extractor.extract("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        assertTrue(result.isFailure)
        assertNull(result.getOrNull())
    }

    @Test
    fun `defense in depth - extract re-validates the host itself`() = runBlocking {
        val error = extractor.extract("https://youtube.com.evil.com/watch?v=abc123").exceptionOrNull()
        assertTrue(error is MediaResolveException.UnsupportedSource)
    }

    @Test
    fun `extractor has no collaborators - structurally network-free`() {
        assertTrue(
            extractor.javaClass.constructors.single().parameterTypes.isEmpty(),
        )
    }
}
