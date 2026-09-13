package com.narcictub.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlValidatorTest {

    @Test
    fun `https url is valid`() {
        assertTrue(UrlValidator.isValidHttpUrl("https://example.com/watch?v=123"))
    }

    @Test
    fun `http url is valid`() {
        assertTrue(UrlValidator.isValidHttpUrl("http://example.com/video.mp4"))
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertTrue(UrlValidator.isValidHttpUrl("  https://example.com/x  "))
        assertEquals("https://example.com/x", UrlValidator.normalize("  https://example.com/x  "))
    }

    @Test
    fun `blank url is invalid`() {
        assertFalse(UrlValidator.isValidHttpUrl(""))
        assertFalse(UrlValidator.isValidHttpUrl("   "))
    }

    @Test
    fun `non-http scheme is invalid`() {
        assertFalse(UrlValidator.isValidHttpUrl("ftp://example.com/file.mp4"))
        assertEquals(
            "Link must start with http:// or https://",
            UrlValidator.validationMessage("ftp://example.com/file.mp4"),
        )
    }

    @Test
    fun `missing scheme is invalid`() {
        assertFalse(UrlValidator.isValidHttpUrl("example.com/video"))
    }

    @Test
    fun `garbage is invalid`() {
        assertFalse(UrlValidator.isValidHttpUrl("not a url"))
    }

    @Test
    fun `valid url has null message`() {
        assertNull(UrlValidator.validationMessage("https://example.com/x"))
    }
}
