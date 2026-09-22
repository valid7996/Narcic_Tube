package com.narcictub.app.data.resolver

import com.narcictub.app.domain.model.MediaInfo
import com.narcictub.app.domain.model.MediaProvider
import com.narcictub.app.domain.resolver.MediaExtractor
import com.narcictub.app.domain.resolver.MediaResolveException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 17 — extractor registry routing: the FIRST extractor that claims a
 * URL performs the extraction; recognized providers without an implemented
 * extractor fail honestly with UnsupportedProvider (no probing, no fake
 * metadata); unrecognized hosts fall through to the direct-media intake.
 */
class ExtractorRegistryMediaResolverTest {

    private class FakeExtractor(
        private val supportedHost: String,
        private val result: Result<MediaInfo>,
        override val priority: Int = 0,
    ) : MediaExtractor {
        val claimed = mutableListOf<String>()

        override fun supports(url: String): Boolean =
            url.contains(supportedHost).also { if (it) claimed.add(url) }

        override suspend fun extract(url: String): Result<MediaInfo> = result
    }

    private fun info(host: String) = MediaInfo(
        sourceUrl = "https://$host/x",
        host = host,
        provider = MediaProvider.UNKNOWN,
        isDirectFile = true,
    )

    private val youtubeFailure = Result.failure<MediaInfo>(
        MediaResolveException.UnsupportedProvider(MediaProvider.YOUTUBE),
    )

    @Test
    fun `recognized provider without an extractor fails with UnsupportedProvider`() = runTest {
        // No extractor claims YouTube — the honest typed failure is returned
        // and the direct intake is never consulted.
        val direct = FakeExtractor("example.com", Result.success(info("example.com")))
        val registry = ExtractorRegistryMediaResolver(linkedSetOf(direct))

        val result = registry.resolve("https://youtube.com/watch?v=1")

        val error = result.exceptionOrNull()
        assertTrue("expected UnsupportedProvider, got $error", error is MediaResolveException.UnsupportedProvider)
        assertEquals(MediaProvider.YOUTUBE, (error as MediaResolveException.UnsupportedProvider).provider)
        assertEquals("the direct intake must not probe recognized providers", 0, direct.claimed.size)
    }

    @Test
    fun `registered provider extractor handles its urls`() = runTest {
        val youtube = FakeExtractor("youtube.com", youtubeFailure)
        val direct = FakeExtractor("example.com", Result.success(info("example.com")))
        val registry = ExtractorRegistryMediaResolver(linkedSetOf(youtube, direct))

        val result = registry.resolve("https://youtube.com/watch?v=1")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is MediaResolveException.UnsupportedProvider)
        // A real extractor would return success — the routing is what we pin:
        assertEquals(listOf("https://youtube.com/watch?v=1"), youtube.claimed)
        assertEquals(0, direct.claimed.size)
    }

    @Test
    fun `unrecognized hosts fall through to the direct-media intake`() = runTest {
        val direct = FakeExtractor("example.com", Result.success(info("example.com")))
        val registry = ExtractorRegistryMediaResolver(linkedSetOf(direct))

        val result = registry.resolve("https://cdn.example.com/clip.mp4")

        assertTrue(result.isSuccess)
        assertEquals("example.com", result.getOrNull()!!.host)
        assertEquals(listOf("https://cdn.example.com/clip.mp4"), direct.claimed)
    }

    @Test
    fun `first claiming extractor wins when several support a url`() = runTest {
        val first = FakeExtractor("example.com", Result.success(info("first.example.com")))
        val second = FakeExtractor("example.com", Result.success(info("second.example.com")))
        val registry = ExtractorRegistryMediaResolver(linkedSetOf(first, second))

        val result = registry.resolve("https://example.com/clip.mp4")

        assertEquals("first.example.com", result.getOrNull()!!.host)
        assertEquals(0, second.claimed.size)
    }

    @Test
    fun `no extractors at all and unknown host yields unsupported source`() = runTest {
        val registry = ExtractorRegistryMediaResolver(emptySet())

        val result = registry.resolve("https://cdn.example.com/clip.mp4")

        assertTrue(result.exceptionOrNull() is MediaResolveException.UnsupportedSource)
    }

    // ===== Phase 19: real Instagram extractor routing =====

    @Test
    fun `instagram urls route to the instagram extractor by priority`() = runTest {
        val instagram = InstagramExtractor()
        val direct = FakeExtractor("instagram", Result.success(info("instagram.com")))
        // Registration order deliberately reversed: priority (100 > 0) must
        // make the Instagram extractor win regardless of registration order.
        val registry = ExtractorRegistryMediaResolver(linkedSetOf(direct, instagram))

        val result = registry.resolve("https://www.instagram.com/p/Cabc123/")

        val error = result.exceptionOrNull()
        assertTrue(
            "expected ExtractionUnavailable from the Instagram extractor, got $error",
            error is MediaResolveException.ExtractionUnavailable,
        )
        assertEquals(MediaProvider.INSTAGRAM, (error as MediaResolveException.ExtractionUnavailable).provider)
        assertEquals("the direct intake must never claim Instagram URLs", 0, direct.claimed.size)
    }

    @Test
    fun `instagram urls never fall through to the direct-media intake`() = runTest {
        val instagram = InstagramExtractor()
        val registry = ExtractorRegistryMediaResolver(linkedSetOf(instagram))

        val error = registry.resolve("https://instagram.com/p/Cabc123/").exceptionOrNull()

        assertTrue(error is MediaResolveException.ExtractionUnavailable)
        assertEquals(MediaProvider.INSTAGRAM, (error as MediaResolveException.ExtractionUnavailable).provider)
    }

    @Test
    fun `youtube urls are not claimed by the instagram extractor`() = runTest {
        val instagram = InstagramExtractor()
        val registry = ExtractorRegistryMediaResolver(linkedSetOf(instagram))

        val error = registry.resolve("https://youtube.com/watch?v=1").exceptionOrNull()

        // Still the honest YouTube routing from Phase 17/18 — no
        // cross-provider claim by the Instagram extractor.
        assertTrue(error is MediaResolveException.UnsupportedProvider)
        assertEquals(MediaProvider.YOUTUBE, (error as MediaResolveException.UnsupportedProvider).provider)
    }

    // ===== Phase 24: YouTube extractor routing =====

    @Test
    fun `youtube urls route to the youtube extractor and yield the blocked verdict`() = runTest {
        val youtube = YouTubeExtractor()
        val direct = FakeExtractor("youtube", Result.success(info("youtube.com")))
        // Priority (100 > 0) must win over registration order.
        val registry = ExtractorRegistryMediaResolver(linkedSetOf(direct, youtube))

        val result = registry.resolve("https://www.youtube.com/watch?v=dQw4w9WgXcQ")

        val error = result.exceptionOrNull()
        assertTrue(
            "expected the YouTube extractor's ExtractionUnavailable verdict, got $error",
            error is MediaResolveException.ExtractionUnavailable,
        )
        assertEquals(MediaProvider.YOUTUBE, (error as MediaResolveException.ExtractionUnavailable).provider)
        assertEquals("the direct intake must never claim YouTube URLs", 0, direct.claimed.size)
    }

    @Test
    fun `youtube urls are not claimed by the instagram extractor even when both are registered`() =
        runTest {
            val youtube = YouTubeExtractor()
            val instagram = InstagramExtractor()
            val registry = ExtractorRegistryMediaResolver(linkedSetOf(instagram, youtube))

            val error = registry.resolve("https://www.youtube.com/watch?v=dQw4w9WgXcQ").exceptionOrNull()

            assertTrue(error is MediaResolveException.ExtractionUnavailable)
            assertEquals(MediaProvider.YOUTUBE, (error as MediaResolveException.ExtractionUnavailable).provider)
        }

    @Test
    fun `instagram urls still route to the instagram extractor when youtube is registered`() = runTest {
        val youtube = YouTubeExtractor()
        val instagram = InstagramExtractor()
        val registry = ExtractorRegistryMediaResolver(linkedSetOf(youtube, instagram))

        val error = registry.resolve("https://instagram.com/p/Cabc123/").exceptionOrNull()

        assertTrue(error is MediaResolveException.ExtractionUnavailable)
        assertEquals(MediaProvider.INSTAGRAM, (error as MediaResolveException.ExtractionUnavailable).provider)
    }
}
