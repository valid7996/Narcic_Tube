package com.narcictub.app.data.local

import android.content.Context
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * PHASE 10 — availability checks behind the URI safety policy. Real
 * filesystem for the app-owned file:// leg; scripted resolver leg
 * (ContentResolver is not unit-testable on the JVM). UNSAFE URIs must
 * return false without touching any backend.
 */
class MediaFileCheckerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class ScriptedChecker(appDirs: List<File>) : MediaFileChecker(
        context = mockk<Context>(relaxed = true),
        uriSafety = MediaUriSafety(appDirs),
    ) {
        var contentResult = true
        var contentCalls = 0
        var fileCalls = 0

        override fun contentResolvable(uriText: String): Boolean {
            contentCalls++
            return contentResult
        }

        override fun appFilePresent(uriText: String): Boolean {
            fileCalls++
            return super.appFilePresent(uriText)
        }
    }

    @Test
    fun `content uri routes to the provider check`() = runBlocking {
        val checker = ScriptedChecker(emptyList())
        checker.contentResult = true
        assertTrue(checker.isAvailable("content://media/external/downloads/7"))
        assertEquals(1, checker.contentCalls)
        assertEquals(0, checker.fileCalls)

        checker.contentResult = false
        assertFalse(checker.isAvailable("content://media/external/downloads/7"))
        assertEquals(2, checker.contentCalls)
    }

    @Test
    fun `app contained file uri checks the real filesystem`() = runBlocking {
        val appDir = tmp.newFolder("app")
        val checker = ScriptedChecker(listOf(appDir))
        val existing = File(appDir, "downloads/clip.mp4").also {
            it.parentFile.mkdirs()
            it.writeText("x")
        }

        assertTrue(checker.isAvailable(existing.toURI().toString()))
        assertEquals(1, checker.fileCalls)
        assertEquals(0, checker.contentCalls)

        val missing = File(appDir, "downloads/gone.mp4")
        assertFalse(checker.isAvailable(missing.toURI().toString()))
    }

    @Test
    fun `unsafe uris never touch any backend`() = runBlocking {
        val checker = ScriptedChecker(emptyList())
        assertFalse(checker.isAvailable("https://example.com/f.mp4"))
        assertFalse(checker.isAvailable("file:///etc/passwd"))
        assertFalse(checker.isAvailable("not a uri"))
        assertFalse(checker.isAvailable(""))
        assertEquals(0, checker.contentCalls)
        assertEquals(0, checker.fileCalls)
    }
}
