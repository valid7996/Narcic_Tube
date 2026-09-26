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
 */
object YtDlpCookies {

    private const val DIRECTORY = "ytdlp"
    private const val FILE_NAME = "cookies.txt"
    private const val MAX_BYTES = 2L * 1024 * 1024

    fun file(context: Context): File = File(File(context.filesDir, DIRECTORY), FILE_NAME)

    fun exists(context: Context): Boolean = file(context).let { it.isFile && it.length() > 0L }

    fun clear(context: Context) {
        runCatching { file(context).delete() }
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
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Header comment or at least one 7-column, tab-separated cookie line. */
    internal fun looksLikeNetscapeCookies(text: String): Boolean =
        text.contains("Netscape HTTP Cookie File") ||
            text.lineSequence().any { line -> line.count { it == '\t' } >= 6 }
}
