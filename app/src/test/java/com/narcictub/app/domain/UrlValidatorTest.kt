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

    // ===== Security regression tests (Phase 4 review findings S1/T1) =====

    @Test
    fun `javascript scheme is rejected`() {
        assertFalse(UrlValidator.isValidHttpUrl("javascript:alert(1)"))
    }

    @Test
    fun `data scheme is rejected`() {
        assertFalse(UrlValidator.isValidHttpUrl("data:text/html,<h1>hi</h1>"))
    }

    @Test
    fun `file scheme is rejected`() {
        assertFalse(UrlValidator.isValidHttpUrl("file:///etc/passwd"))
    }

    @Test
    fun `intent scheme is rejected`() {
        assertFalse(UrlValidator.isValidHttpUrl("intent://example.com/#Intent;scheme=https;end"))
    }

    @Test
    fun `blob scheme is rejected`() {
        assertFalse(UrlValidator.isValidHttpUrl("blob:https://example.com/uuid"))
    }

    @Test
    fun `content scheme is rejected`() {
        assertFalse(UrlValidator.isValidHttpUrl("content://media/external/video/1"))
    }

    @Test
    fun `empty host is rejected`() {
        assertFalse(UrlValidator.isValidHttpUrl("http://"))
        assertFalse(UrlValidator.isValidHttpUrl("https:///path"))
    }

    @Test
    fun `uppercase HTTPS is accepted via case-insensitive scheme`() {
        assertTrue(UrlValidator.isValidHttpUrl("HTTPS://EXAMPLE.COM/X"))
    }

    @Test
    fun `embedded credentials are rejected with explicit policy message`() {
        assertFalse(UrlValidator.isValidHttpUrl("https://user:pass@example.com/video"))
        assertEquals(
            "Links with embedded usernames or passwords are not allowed",
            UrlValidator.validationMessage("https://user:pass@example.com/video"),
        )
    }

    @Test
    fun `username-only userinfo is also rejected`() {
        assertFalse(UrlValidator.isValidHttpUrl("https://alice@example.com/video"))
    }

    @Test
    fun `userinfo without scheme is invalid`() {
        assertFalse(UrlValidator.isValidHttpUrl("user:pass@example.com"))
    }

    @Test
    fun `unusual port is still a structurally valid http url`() {
        assertTrue(UrlValidator.isValidHttpUrl("http://example.com:8080/video"))
    }

    @Test
    fun `ipv6 host is accepted`() {
        assertTrue(UrlValidator.isValidHttpUrl("http://[::1]:8080/x"))
    }

    @Test
    fun `malformed urls are rejected`() {
        assertFalse(UrlValidator.isValidHttpUrl("http://exa mple.com/x"))
        assertFalse(UrlValidator.isValidHttpUrl("https://example.com:notaport/x"))
    }

    @Test
    fun `localhost and private ips pass structural validation by design`() {
        // Policy: accepted structurally; no fetch exists yet. Revisit only
        // when an automatic (non-user-initiated) network component lands.
        assertTrue(UrlValidator.isValidHttpUrl("http://localhost/x"))
        assertTrue(UrlValidator.isValidHttpUrl("http://127.0.0.1/x"))
        assertTrue(UrlValidator.isValidHttpUrl("http://192.168.1.1/x"))
    }
}
