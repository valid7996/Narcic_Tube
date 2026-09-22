package com.narcictub.app.data.local

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * PHASE 10 — the media-open URI safety policy. Pins exactly what may be
 * handed to an Intent: content:// URIs (NarcicTub's Q+ publishing form) and
 * file:// URIs contained in the app's own storage tree (the pre-Q publisher
 * form). Everything else — foreign schemes, traversal escapes, credentials,
 * malformed input — is UNSAFE.
 */
class MediaUriSafetyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var safety: MediaUriSafety

    private fun safetyWithRoots(vararg roots: File): MediaUriSafety =
        MediaUriSafety(roots.map { it.canonicalFile })

    @Test
    fun `content uri with authority is accepted`() {
        safety = safetyWithRoots(tmp.root)
        assertEquals(
            MediaUriSafety.Kind.CONTENT,
            safety.classify("content://media/external/downloads/42"),
        )
        assertTrue(safety.isSafelyOpenable("content://media/external/downloads/42"))
    }

    @Test
    fun `content uri without authority is rejected`() {
        safety = safetyWithRoots(tmp.root)
        assertEquals(MediaUriSafety.Kind.UNSAFE, safety.classify("content:///orphan"))
        assertFalse(safety.isSafelyOpenable("content:///orphan"))
    }

    @Test
    fun `file uri inside the app tree is accepted and resolves to the path`() {
        val appDir = tmp.newFolder("app-external-files")
        val target = File(appDir, "downloads/clip.mp4").also { it.parentFile.mkdirs() }
        safety = safetyWithRoots(appDir)

        val uriText = target.toURI().toString() // file:///…/app-external-files/downloads/clip.mp4
        assertEquals(MediaUriSafety.Kind.APP_FILE, safety.classify(uriText))
        assertEquals(target.canonicalFile, safety.appFilePath(uriText)?.canonicalFile)
    }

    @Test
    fun `file uri outside the app tree is rejected`() {
        val appDir = tmp.newFolder("app")
        val outside = tmp.newFolder("outside")
        val target = File(outside, "secret.txt")
        safety = safetyWithRoots(appDir)

        assertEquals(MediaUriSafety.Kind.UNSAFE, safety.classify(target.toURI().toString()))
        assertNull(safety.appFilePath(target.toURI().toString()))
    }

    @Test
    fun `traversal escaping the app tree is rejected`() {
        val appDir = tmp.newFolder("app")
        safety = safetyWithRoots(appDir)

        // Sibling of the app dir (outside): built through File.toURI() so
        // the string is a valid file URI on every platform.
        val outside = File(tmp.root, "escaped.bin").toURI().toString()
        assertEquals(MediaUriSafety.Kind.UNSAFE, safety.classify(outside))

        // Literal ".." segment escaping the app dir.
        val dotDot = File(appDir, "../escaped.bin").toURI().toString()
        assertEquals(MediaUriSafety.Kind.UNSAFE, safety.classify(dotDot))
    }

    @Test
    fun `encoded traversal escaping the app tree is rejected`() {
        val appDir = tmp.newFolder("app")
        safety = safetyWithRoots(appDir)
        // %2e%2e decodes to ".." — the canonical containment check must
        // still see the escape after the URI's path is decoded.
        val encoded = appDir.toURI().toString().trimEnd('/') + "/%2e%2e/escaped.bin"
        assertEquals(MediaUriSafety.Kind.UNSAFE, safety.classify(encoded))
    }

    @Test
    fun `foreign and dangerous schemes are rejected`() {
        safety = safetyWithRoots(tmp.root)
        for (uri in listOf(
            "https://example.com/file.mp4",
            "http://example.com/file.mp4",
            "ftp://example.com/file.mp4",
            "javascript:alert(1)",
            "data:text/html,hello",
            "file://attacker.example.com/etc/passwd",
            "not a uri at all",
            "",
            "   ",
        )) {
            assertEquals("expected UNSAFE for '$uri'", MediaUriSafety.Kind.UNSAFE, safety.classify(uri))
            assertFalse(safety.isSafelyOpenable(uri))
        }
    }

    @Test
    fun `null input is rejected`() {
        safety = safetyWithRoots(tmp.root)
        assertEquals(MediaUriSafety.Kind.UNSAFE, safety.classify(null))
        assertFalse(safety.isSafelyOpenable(null))
    }

    @Test
    fun `userinfo in any uri is rejected`() {
        safety = safetyWithRoots(tmp.root)
        assertEquals(
            MediaUriSafety.Kind.UNSAFE,
            safety.classify("content://user:pass@media/external/downloads/1"),
        )
    }

    @Test
    fun `empty app dirs reject every file uri`() {
        safety = MediaUriSafety(emptyList())
        assertEquals(MediaUriSafety.Kind.UNSAFE, safety.classify("file:///anything.bin"))
    }
}
