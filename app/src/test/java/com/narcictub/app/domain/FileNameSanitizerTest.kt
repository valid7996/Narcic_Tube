package com.narcictub.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FileNameSanitizerTest {

    // ===== sanitization =====

    @Test
    fun `plain name is kept`() {
        assertEquals("video.mp4", FileNameSanitizer.sanitize("video.mp4"))
    }

    @Test
    fun `colon and forbidden characters are removed`() {
        // ':' '*' '?' '<' '>' '|' are stripped; '.' is a legal filename char.
        assertEquals("report.pdf", FileNameSanitizer.sanitize("report:*.pdf"))
        assertEquals("reportpdf", FileNameSanitizer.sanitize("report:pdf"))
        assertEquals("ab", FileNameSanitizer.sanitize("a<b>"))
        assertEquals("abc", FileNameSanitizer.sanitize("a?b*c"))
    }

    @Test
    fun `control characters are removed`() {
        assertEquals("name", FileNameSanitizer.sanitize("na\u0000me\u001f"))
    }

    @Test
    fun `leading dots are stripped so hidden files cannot be created`() {
        assertEquals("name", FileNameSanitizer.sanitize(".name"))
        assertEquals("hidden", FileNameSanitizer.sanitize("..hidden"))
    }

    @Test
    fun `blank input falls back to default`() {
        assertEquals("download", FileNameSanitizer.sanitize("   "))
        assertEquals("download", FileNameSanitizer.sanitize(null))
    }

    @Test
    fun `dot-only input falls back`() {
        assertEquals("download", FileNameSanitizer.sanitize("..."))
        assertEquals("download", FileNameSanitizer.sanitize("."))
        assertEquals("download", FileNameSanitizer.sanitize(".."))
    }

    // ===== traversal — the critical security cases =====

    @Test
    fun `path traversal segments are eliminated by keeping only last segment`() {
        assertEquals("passwd", FileNameSanitizer.sanitize("../../etc/passwd"))
        assertEquals("secret", FileNameSanitizer.sanitize("..\\..\\windows\\secret"))
        assertEquals("x.mp4", FileNameSanitizer.sanitize("a/b/c/x.mp4"))
    }

    @Test
    fun `traversal-only input falls back to default`() {
        assertEquals("download", FileNameSanitizer.sanitize("../.."))
        assertEquals("download", FileNameSanitizer.sanitize("..\\.."))
    }

    @Test
    fun `long names are capped`() {
        val long = "a".repeat(500) + ".mp4"
        assertEquals(FileNameSanitizer.MAX_LENGTH, FileNameSanitizer.sanitize(long).length)
    }

    // ===== URL extraction =====

    @Test
    fun `extracts last path segment from url`() {
        assertEquals("song.mp3", FileNameSanitizer.fromUrl("https://example.com/music/song.mp3"))
        assertEquals("file.mp4", FileNameSanitizer.fromUrl("https://example.com/file.mp4?query=1"))
    }

    @Test
    fun `url without file segment returns null`() {
        assertNull(FileNameSanitizer.fromUrl("https://example.com/"))
        assertNull(FileNameSanitizer.fromUrl("https://example.com"))
        assertNull(FileNameSanitizer.fromUrl("https://example.com/dir/"))
    }

    @Test
    fun `encoded traversal in url is not decoded`() {
        // %2e%2e%2f must NOT become ../ — rawPath keeps the encoding, and
        // only the segment after the LAST literal slash is kept, so the
        // encoded traversal survives as a harmless flat name (no slash, no
        // traversal semantics anywhere).
        assertEquals("%2e%2e%2fetc%2fpasswd", FileNameSanitizer.fromUrl("https://example.com/%2e%2e%2fetc%2fpasswd"))
    }

    @Test
    fun `malformed url returns null`() {
        assertNull(FileNameSanitizer.fromUrl("not a url"))
    }
}
