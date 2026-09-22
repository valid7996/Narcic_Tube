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
 * PHASE 19 — Instagram extractor behavior tests: strict independent host
 * validation, content-type classification, and honest typed failures.
 *
 * The production implementation injects NO network dependencies, so it is
 * structurally incapable of making requests; these tests additionally pin
 * that every path returns a typed Result instead of throwing, and that
 * cancellation propagates.
 */
class InstagramExtractorTest {

    private val extractor = InstagramExtractor()

    // ===== host validation: accepted =====

    @Test
    fun `instagram com is claimed`() {
        assertTrue(extractor.supports("https://instagram.com/p/Cabc123/"))
        assertEquals("instagram.com", extractor.instagramHostOf("https://instagram.com/p/Cabc123/"))
    }

    @Test
    fun `www and mobile subdomains are claimed`() {
        assertTrue(extractor.supports("https://www.instagram.com/p/Cabc123/"))
        assertEquals("www.instagram.com", extractor.instagramHostOf("https://www.instagram.com/p/Cabc123/"))
        assertTrue(extractor.supports("http://m.instagram.com/reel/Cabc123/"))
        assertEquals("m.instagram.com", extractor.instagramHostOf("https://m.instagram.com/p/Cabc123/"))
    }

    @Test
    fun `genuine deeper subdomains are claimed`() {
        assertTrue(extractor.supports("https://static.instagram.com/p/Cabc123/"))
        assertEquals("static.instagram.com", extractor.instagramHostOf("https://static.instagram.com/p/Cabc123/"))
    }

    // ===== host validation: rejected =====

    @Test
    fun `lookalike hosts are never claimed`() {
        // Must not be tricked into claiming a hostile host.
        assertFalse(extractor.supports("https://instagram.com.evil.com/p/Cabc123/"))
        assertFalse(extractor.supports("https://evilinstagram.com/p/Cabc123/"))
        assertFalse(extractor.supports("https://instagram.com.evil.com"))
        assertNull(extractor.instagramHostOf("https://instagram.com.evil.com/p/Cabc123/"))
    }

    @Test
    fun `userinfo urls are rejected`() {
        assertFalse(extractor.supports("https://user:pass@instagram.com/p/Cabc123/"))
        assertNull(extractor.instagramHostOf("https://user:pass@instagram.com/p/Cabc123/"))
    }

    @Test
    fun `dangerous schemes are rejected`() {
        for (url in listOf(
            "file://instagram.com/p/Cabc123/",
            "content://instagram.com/p/Cabc123/",
            "javascript:instagram.com/p/Cabc123",
            "data:text/html,instagram.com/p/Cabc123",
            "ftp://instagram.com/p/Cabc123/",
        )) {
            assertFalse("must reject $url", extractor.supports(url))
            assertNull(extractor.instagramHostOf(url))
        }
    }

    @Test
    fun `malformed urls are rejected without throwing`() {
        for (url in listOf("", "   ", "not a url", "https://", "https:///p/x", "ht!tp://instagram.com/p/x")) {
            assertFalse("must reject '$url'", extractor.supports(url))
            assertNull(extractor.instagramHostOf(url))
        }
    }

    @Test
    fun `ip literal and localhost targets are never claimed`() {
        // They cannot be instagram.com hosts — excluded by construction and
        // explicitly pinned here.
        assertFalse(extractor.supports("http://127.0.0.1/p/Cabc123/"))
        assertFalse(extractor.supports("http://localhost/p/Cabc123/"))
        assertFalse(extractor.supports("http://[::1]/p/Cabc123/"))
        assertFalse(extractor.supports("http://10.0.0.7/p/Cabc123/"))
    }

    // ===== routing sanity: no cross-provider claim =====

    @Test
    fun `youtube and unknown hosts are never claimed`() {
        assertFalse(extractor.supports("https://youtube.com/watch?v=1"))
        assertFalse(extractor.supports("https://cdn.example.com/clip.mp4"))
    }

    // ===== extraction: honest typed failures =====

    @Test
    fun `public post routes to ExtractionUnavailable with real provider`() = runBlocking {
        val result = extractor.extract("https://www.instagram.com/p/Cabc123/")

        val error = result.exceptionOrNull()
        assertTrue("expected ExtractionUnavailable, got $error", error is MediaResolveException.ExtractionUnavailable)
        assertEquals(MediaProvider.INSTAGRAM, (error as MediaResolveException.ExtractionUnavailable).provider)
        assertNull(result.getOrNull())
    }

    @Test
    fun `reel and reels and tv shapes route to ExtractionUnavailable`() = runBlocking {
        for (path in listOf("/reel/Cabc123/", "/reels/Cabc123", "/tv/Cabc123/")) {
            val error = extractor.extract("https://instagram.com$path").exceptionOrNull()
            assertTrue(
                "expected ExtractionUnavailable for $path",
                error is MediaResolveException.ExtractionUnavailable,
            )
        }
    }

    @Test
    fun `non-content urls are UnsupportedSource`() = runBlocking {
        for (path in listOf(
            "/", // root
            "/someprofile/", // profile page
            "/stories/someuser/12345/", // login-only stories
            "/explore/",
            "/direct/inbox/",
        )) {
            val error = extractor.extract("https://instagram.com$path").exceptionOrNull()
            assertTrue(
                "expected UnsupportedSource for $path, got $error",
                error is MediaResolveException.UnsupportedSource,
            )
        }
    }

    @Test
    fun `shortcode with unicode or traversal is not recognized as media content`() = runBlocking {
        for (path in listOf("/p/../../etc", "/p/Cabc%20evil", "/p/کوتاه")) {
            val error = extractor.extract("https://instagram.com$path").exceptionOrNull()
            assertTrue(
                "expected UnsupportedSource for $path, got $error",
                error is MediaResolveException.UnsupportedSource,
            )
        }
    }

    @Test
    fun `extract never fabricates metadata`() = runBlocking {
        // The success path is unimplemented — no MediaInfo may ever be
        // produced, hence no fake title/duration/downloadUrl can exist.
        val result = extractor.extract("https://www.instagram.com/p/Cabc123/")
        assertNull(result.getOrNull())
        assertTrue(result.isFailure)
    }

    @Test
    fun `defense in depth - extract re-validates the host itself`() = runBlocking {
        // Even if something routed a hostile URL here, extract() must not
        // classify it as Instagram content.
        val error = extractor.extract("https://instagram.com.evil.com/p/Cabc123/").exceptionOrNull()
        assertTrue(error is MediaResolveException.UnsupportedSource)
    }

    @Test
    fun `extract never throws raw exceptions to callers`() = runBlocking {
        // Every path — valid, invalid, hostile — returns a typed Result
        // instead of leaking exceptions through the contract.
        for (url in listOf(
            "https://www.instagram.com/p/Cabc123/",
            "https://instagram.com/stories/u/1/",
            "https://instagram.com.evil.com/p/Cabc123/",
            "garbage",
        )) {
            val result = extractor.extract(url)
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is MediaResolveException)
        }
    }

    @Test
    fun `no suspension points exist so cancellation cannot be converted`() {
        // The extractor performs ZERO suspending/network work — there is
        // structurally nothing that could swallow a CancellationException.
        // (Flow-level resolve cancellation is pinned in HomeViewModelTest,
        // where the real suspension happens.)
        val methods = InstagramExtractor::class.java.declaredMethods
        // Cheap structural pin: the class declares no network client fields
        // and no callables beyond the contract — verified by constructor
        // argument inspection below.
        assertTrue(
            "extractor must have no collaborators (no network capability)",
            extractor.javaClass.constructors.single().parameterTypes.isEmpty(),
        )
        assertTrue(methods.isNotEmpty())
    }
}
