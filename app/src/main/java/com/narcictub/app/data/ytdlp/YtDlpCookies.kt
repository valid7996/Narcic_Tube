package com.narcictub.app.data.ytdlp

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Optional login session for yt-dlp, imported by the user as a Netscape-format
 * `cookies.txt` (exported from a browser they are logged in with). Instagram
 * and age-restricted / bot-checked YouTube videos often refuse anonymous
 * requests; with cookies yt-dlp behaves like that logged-in browser.
 *
 * PRIVACY: the file lives in app-private storage, is excluded from cloud
 * backup / device transfer (see the backup XML rules), is only ever passed to
 * yt-dlp for a YouTube/Instagram request, and its content is never logged.
 *
 * DEFAULT SESSION (v1.3.6): a cleaned cookies.txt is bundled in the APK
 * (assets/default_cookies.txt) with the developer's separate throwaway
 * YouTube + Instagram sessions, so downloads work out of the box. It is
 * installed ONCE, on first use, and only while the user has never had a
 * cookies file: a user import or in-app login always wins, and Remove
 * deletes the file without re-seeding it. A bundled session is visible
 * inside the APK (any bundled secret is) — that is why it belongs to
 * throwaway accounts, never a main account.
 */
object YtDlpCookies {

    private const val DIRECTORY = "ytdlp"
    private const val FILE_NAME = "cookies.txt"
    private const val MAX_BYTES = 2L * 1024 * 1024
    private const val DEFAULT_ASSET_NAME = "default_cookies.txt"
    private const val PREFS_NAME = "ytdlp_cookies"
    private const val KEY_DEFAULT_SEEDED = "default_cookies_seeded"
    private const val KEY_SOURCE_DEFAULT = "cookies_source_default"

    fun file(context: Context): File = File(File(context.filesDir, DIRECTORY), FILE_NAME)

    /**
     * True when a usable cookies.txt is present. The first call on a fresh
     * install seeds the bundled default session (see [ensureDefaultSession]);
     * afterwards this is a plain file check, so the seeding cost is paid once.
     */
    fun exists(context: Context): Boolean {
        ensureDefaultSession(context)
        return file(context).let { it.isFile && it.length() > 0L }
    }

    /** True when the current cookies.txt is the bundled default, not a user import/login. */
    fun isDefaultSession(context: Context): Boolean {
        if (!exists(context)) return false
        val prefs = prefs(context)
        return prefs.getBoolean(KEY_DEFAULT_SEEDED, false) &&
            prefs.getBoolean(KEY_SOURCE_DEFAULT, false)
    }

    /**
     * Copies the bundled default session into private storage, once per
     * install and only if no cookies file exists yet. Never throws and never
     * overwrites: a missing/corrupt asset simply means "no session".
     * Blocking I/O — call off the main thread (all current callers are).
     */
    fun ensureDefaultSession(context: Context) {
        try {
            if (file(context).let { it.isFile && it.length() > 0L }) return
            val prefs = prefs(context)
            if (prefs.getBoolean(KEY_DEFAULT_SEEDED, false)) return
            val text = readDefaultAsset(context) ?: return
            val target = file(context)
            target.parentFile?.mkdirs()
            target.writeText(text)
            prefs.edit()
                .putBoolean(KEY_DEFAULT_SEEDED, true)
                .putBoolean(KEY_SOURCE_DEFAULT, true)
                .apply()
        } catch (_: Exception) {
            // No asset, no storage, no session — downloads continue anonymously.
        }
    }

    /**
     * Re-installs the bundled default session on explicit request
     * (Settings → Restore default), replacing whatever is there.
     * Returns false when the asset is missing or unreadable.
     */
    fun restoreDefaultSession(context: Context): Boolean {
        return try {
            val text = readDefaultAsset(context) ?: return false
            val target = file(context)
            target.parentFile?.mkdirs()
            target.writeText(text)
            prefs(context).edit()
                .putBoolean(KEY_DEFAULT_SEEDED, true)
                .putBoolean(KEY_SOURCE_DEFAULT, true)
                .apply()
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Reads the bundled default session; null when it is missing or not a cookies file. */
    private fun readDefaultAsset(context: Context): String? {
        return try {
            val text = context.assets.open(DEFAULT_ASSET_NAME)
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (!looksLikeNetscapeCookies(text)) null else text
        } catch (_: Exception) {
            null
        }
    }

    fun clear(context: Context) {
        runCatching { file(context).delete() }
        // The session is gone, but the seeded flag stays set: removing the
        // session is the user's choice and must not silently re-seed it.
        // (Restore it explicitly via restoreDefaultSession.)
        runCatching { prefs(context).edit().putBoolean(KEY_SOURCE_DEFAULT, false).apply() }
    }

    /**
     * Copies the picked document into private storage after a bounded read
     * and a sanity check that it really is a cookies.txt. Returns false (and
     * stores nothing) otherwise. Blocking I/O — call off the main thread.
     */
    fun import(context: Context, uri: Uri): Boolean {
        return try {
            val text = context.contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArrayOutputStream()
                val chunk = ByteArray(8 * 1024)
                var total = 0L
                while (true) {
                    val read = input.read(chunk)
                    if (read < 0) break
                    total += read
                    if (total > MAX_BYTES) return false
                    buffer.write(chunk, 0, read)
                }
                buffer.toString(Charsets.UTF_8.name())
            } ?: return false
            if (!looksLikeNetscapeCookies(text)) return false
            val target = file(context)
            target.parentFile?.mkdirs()
            target.writeText(text)
            prefs(context).edit().putBoolean(KEY_SOURCE_DEFAULT, false).apply()
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Header comment or at least one 7-column, tab-separated cookie line. */
    internal fun looksLikeNetscapeCookies(text: String): Boolean =
        text.contains("Netscape HTTP Cookie File") ||
            text.lineSequence().any { line -> line.count { it == '\t' } >= 6 }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * HONEY: ذخیره سشن لاگین داخل اپ — کوکی‌های WebView اینستاگرام به
     * فرمت Netscape تبدیل و در همان فایلی که yt-dlp و photo resolver
     * می‌خوانند ذخیره می‌شود. هیچ محتوایی لاگ نمی‌شود.
     */
    fun saveInstagramSession(context: Context, cookieHeader: String): Boolean {
        return try {
            val expiry = (System.currentTimeMillis() / 1000) + 365L * 24 * 60 * 60
            val lines = cookieHeader.split("; ").mapNotNull { pair ->
                val idx = pair.indexOf('=')
                if (idx <= 0) return@mapNotNull null
                val name = pair.substring(0, idx).trim()
                val value = pair.substring(idx + 1).trim()
                if (name.isEmpty() || value.isEmpty()) return@mapNotNull null
                ".instagram.com\tTRUE\t/\tTRUE\t$expiry\t$name\t$value"
            }
            if (lines.isEmpty()) return false
            val target = file(context)
            target.parentFile?.mkdirs()
            target.writeText(
                "# Netscape HTTP Cookie File\n" + lines.joinToString("\n") + "\n",
            )
            prefs(context).edit().putBoolean(KEY_SOURCE_DEFAULT, false).apply()
            true
        } catch (_: Exception) {
            false
        }
    }
}
