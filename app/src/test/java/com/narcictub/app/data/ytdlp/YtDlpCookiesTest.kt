package com.narcictub.app.data.ytdlp

import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import android.content.res.AssetManager
import android.net.Uri
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3.6 — bundled default session: seeded once from the APK asset, never
 * overwriting a user file, replaceable by import/login, removable without
 * re-seeding, restorable on request.
 */
class YtDlpCookiesTest {

    private val tempDirs = mutableListOf<File>()

    @After
    fun tearDown() {
        tempDirs.forEach { it.deleteRecursively() }
        tempDirs.clear()
    }

    private fun tempDir(): File {
        val dir = Files.createTempDirectory("ytdlp-cookies-test").toFile()
        tempDirs += dir
        return dir
    }

    private fun fakePrefs(store: MutableMap<String, Any?> = mutableMapOf()): SharedPreferences {
        val prefs = mockk<SharedPreferences>()
        every { prefs.getBoolean(any(), any()) } answers {
            store[firstArg<String>()] as? Boolean ?: secondArg()
        }
        every { prefs.edit() } answers {
            val editor = mockk<SharedPreferences.Editor>()
            every { editor.putBoolean(any(), any()) } answers {
                store[firstArg<String>()] = secondArg<Boolean>()
                editor
            }
            every { editor.apply() } just Runs
            editor
        }
        return prefs
    }

    private fun fakeContext(
        filesDir: File,
        assetText: String?,
        prefs: SharedPreferences,
        resolver: ContentResolver? = null,
    ): Context {
        val context = mockk<Context>()
        every { context.filesDir } returns filesDir
        val assets = mockk<AssetManager>()
        if (assetText != null) {
            // Production calls the 1-arg open(String); stub it AND the 2-arg
            // overload (they are separate Java overloads — stubbing only the
            // 2-arg one leaves the 1-arg call unstubbed, which is exactly
            // what made CI fail: the unstubbed call throws, the catch in
            // readDefaultAsset swallows it, and seeding silently never runs).
            // answers { } gives every call a FRESH stream — the restore test
            // opens the asset twice and a shared stream would be exhausted.
            every { assets.open(any()) } answers { assetText.byteInputStream() }
            every { assets.open(any(), any()) } answers { assetText.byteInputStream() }
        } else {
            every { assets.open(any()) } throws FileNotFoundException("no asset")
            every { assets.open(any(), any()) } throws FileNotFoundException("no asset")
        }
        every { context.assets } returns assets
        every { context.getSharedPreferences(any(), any()) } returns prefs
        if (resolver != null) {
            every { context.contentResolver } returns resolver
        }
        return context
    }

    private fun seedContext(
        filesDir: File,
        assetText: String?,
        prefs: SharedPreferences,
    ): Context = fakeContext(filesDir, assetText, prefs)

    @Test
    fun `netscape header is accepted`() {
        assertTrue(YtDlpCookies.looksLikeNetscapeCookies("# Netscape HTTP Cookie File\n"))
    }

    @Test
    fun `seven column tab separated lines are accepted`() {
        val line = ".instagram.com\tTRUE\t/\tTRUE\t1893456000\tsessionid\tabc"

        assertTrue(YtDlpCookies.looksLikeNetscapeCookies(line))
    }

    @Test
    fun `arbitrary text and json are rejected`() {
        assertFalse(YtDlpCookies.looksLikeNetscapeCookies("hello world"))
        assertFalse(YtDlpCookies.looksLikeNetscapeCookies("""[{"name":"sessionid","value":"abc"}]"""))
        assertFalse(YtDlpCookies.looksLikeNetscapeCookies(""))
    }

    @Test
    fun `default session is seeded from the asset when no file exists`() {
        val dir = tempDir()
        val prefs = fakePrefs()
        val asset = "# Netscape HTTP Cookie File\n" +
            ".instagram.com\tTRUE\t/\tTRUE\t1893456000\tsessionid\tseeded\n"
        val context = seedContext(dir, asset, prefs)

        assertTrue(YtDlpCookies.exists(context))
        assertTrue(YtDlpCookies.isDefaultSession(context))
        assertEquals(asset, YtDlpCookies.file(context).readText())
    }

    @Test
    fun `missing asset never breaks exists`() {
        val dir = tempDir()
        val context = seedContext(dir, null, fakePrefs())

        assertFalse(YtDlpCookies.exists(context))
        assertFalse(YtDlpCookies.isDefaultSession(context))
    }

    @Test
    fun `non-cookie asset is not seeded`() {
        val dir = tempDir()
        val context = seedContext(dir, "hello world", fakePrefs())

        assertFalse(YtDlpCookies.exists(context))
    }

    @Test
    fun `existing file is never overwritten by seeding`() {
        val dir = tempDir()
        val prefs = fakePrefs()
        val userText = "# Netscape HTTP Cookie File\n" +
            ".youtube.com\tTRUE\t/\tTRUE\t1893456000\tSID\tuser\n"
        val target = YtDlpCookies.file(fakeContext(dir, null, prefs))
        target.parentFile?.mkdirs()
        target.writeText(userText)
        val context = seedContext(dir, "# Netscape HTTP Cookie File\n.instagram.com\tTRUE\t/\tTRUE\t1893456000\tsessionid\tseeded\n", prefs)

        assertTrue(YtDlpCookies.exists(context))
        assertEquals(userText, YtDlpCookies.file(context).readText())
        assertFalse(YtDlpCookies.isDefaultSession(context))
    }

    @Test
    fun `user import replaces the default`() {
        val dir = tempDir()
        val prefs = fakePrefs()
        val asset = "# Netscape HTTP Cookie File\n" +
            ".instagram.com\tTRUE\t/\tTRUE\t1893456000\tsessionid\tseeded\n"
        val importedText = "# Netscape HTTP Cookie File\n" +
            ".youtube.com\tTRUE\t/\tTRUE\t1893456000\tSID\tuser\n"
        val resolver = mockk<ContentResolver>()
        every { resolver.openInputStream(any()) } returns importedText.byteInputStream()
        val context = fakeContext(dir, asset, prefs, resolver)

        assertTrue(YtDlpCookies.exists(context))
        assertTrue(YtDlpCookies.isDefaultSession(context))
        assertTrue(YtDlpCookies.import(context, mockk<Uri>()))
        assertEquals(importedText, YtDlpCookies.file(context).readText())
        assertFalse(YtDlpCookies.isDefaultSession(context))
    }

    @Test
    fun `clear removes the file and does not re-seed`() {
        val dir = tempDir()
        val prefs = fakePrefs()
        val asset = "# Netscape HTTP Cookie File\n" +
            ".instagram.com\tTRUE\t/\tTRUE\t1893456000\tsessionid\tseeded\n"
        val context = seedContext(dir, asset, prefs)

        assertTrue(YtDlpCookies.exists(context))
        YtDlpCookies.clear(context)
        assertFalse(YtDlpCookies.exists(context))
        assertFalse(YtDlpCookies.isDefaultSession(context))
    }

    @Test
    fun `restoreDefaultSession brings the default back after clear`() {
        val dir = tempDir()
        val prefs = fakePrefs()
        val asset = "# Netscape HTTP Cookie File\n" +
            ".instagram.com\tTRUE\t/\tTRUE\t1893456000\tsessionid\tseeded\n"
        val context = seedContext(dir, asset, prefs)

        assertTrue(YtDlpCookies.exists(context))
        YtDlpCookies.clear(context)
        assertFalse(YtDlpCookies.exists(context))
        assertTrue(YtDlpCookies.restoreDefaultSession(context))
        assertTrue(YtDlpCookies.exists(context))
        assertTrue(YtDlpCookies.isDefaultSession(context))
        assertEquals(asset, YtDlpCookies.file(context).readText())
    }
}
