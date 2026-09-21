package com.narcictub.app.domain.share

import com.narcictub.app.domain.share.SharedTextUrl.Extraction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 17 — conservative URL extraction from shared text: exactly one
 * usable link is required; anything ambiguous or invalid is rejected
 * rather than guessed.
 */
class SharedTextUrlTest {

    @Test
    fun `plain url as the complete text is accepted`() {
        val result = SharedTextUrl.extract("https://example.com/video.mp4")
        assertEquals(Extraction.Single("https://example.com/video.mp4"), result)
    }

    @Test
    fun `surrounding whitespace is handled`() {
        val result = SharedTextUrl.extract("   https://example.com/clip.mp4  \n")
        assertEquals(Extraction.Single("https://example.com/clip.mp4"), result)
    }

    @Test
    fun `full-text url keeps its query string intact`() {
        val result = SharedTextUrl.extract("https://example.com/watch?v=123&t=4")
        assertEquals(Extraction.Single("https://example.com/watch?v=123&t=4"), result)
    }

    @Test
    fun `url inside a sentence is extracted with trailing punctuation stripped`() {
        val result = SharedTextUrl.extract("Check this out: https://example.com/clip.mp4, it's great!")
        assertEquals(Extraction.Single("https://example.com/clip.mp4"), result)
    }

    @Test
    fun `text without any url is rejected`() {
        assertEquals(Extraction.None, SharedTextUrl.extract("look at this amazing video"))
    }

    @Test
    fun `non-http scheme only is rejected`() {
        assertEquals(Extraction.None, SharedTextUrl.extract("ftp://example.com/file.mp4"))
    }

    @Test
    fun `credential-bearing url is rejected`() {
        assertEquals(
            Extraction.None,
            SharedTextUrl.extract("https://user:pass@example.com/private.mp4"),
        )
    }

    @Test
    fun `two different urls are ambiguous and never silently picked`() {
        val result = SharedTextUrl.extract(
            "https://example.com/a.mp4 and also https://example.com/b.mp4",
        )
        assertEquals(Extraction.Ambiguous(2), result)
    }

    @Test
    fun `the same url repeated stays a single deterministic choice`() {
        val result = SharedTextUrl.extract(
            "https://example.com/a.mp4, https://example.com/a.mp4.",
        )
        assertEquals(Extraction.Single("https://example.com/a.mp4"), result)
    }

    @Test
    fun `empty blank and null text are rejected`() {
        assertEquals(Extraction.None, SharedTextUrl.extract(""))
        assertEquals(Extraction.None, SharedTextUrl.extract("   "))
        assertEquals(Extraction.None, SharedTextUrl.extract(null))
    }

    @Test
    fun `only http and https schemes are candidates`() {
        val result = SharedTextUrl.extract(
            "javascript:alert(1) https://example.com/ok.mp4 content://media/x",
        )
        assertTrue(result is Extraction.Single)
        assertEquals("https://example.com/ok.mp4", (result as Extraction.Single).url)
    }
}
