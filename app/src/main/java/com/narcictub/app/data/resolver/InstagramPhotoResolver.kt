package com.narcictub.app.data.resolver

import android.content.Context
import com.narcictub.app.data.ytdlp.YtDlpCookies
import com.narcictub.app.domain.model.MediaInfo
import com.narcictub.app.domain.model.MediaProvider
import com.narcictub.app.domain.model.MediaVariant
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.HttpsURLConnection

/**
 * Instagram PHOTO posts: yt-dlp extracts video/audio but a photo post has
 * no video stream, so extraction fails with "no media". This resolver fills
 * that gap the honest way — it reads the post page's Open Graph metadata
 * (og:image = the real photo for public posts) and hands back a direct
 * image URL that the existing download pipeline publishes like any file.
 *
 * Reliability notes (no pretending): public posts expose og:image without a
 * login; private/removed posts do not, and the resolver returns null so the
 * original typed error surfaces. When the user imported an Instagram
 * cookies.txt in Settings, its cookies are attached to the page request,
 * which covers more posts. Media type is pinned to image/jpeg — og:image on
 * Instagram is always a JPEG rendition.
 */
@Singleton
class InstagramPhotoResolver @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    suspend fun resolvePhotoPost(pageUrl: String): MediaInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val connection = URL(pageUrl).openConnection() as HttpsURLConnection
            try {
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.instanceFollowRedirects = true
                connection.setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36",
                )
                instagramCookieHeader()?.let { connection.setRequestProperty("Cookie", it) }

                val html = connection.inputStream.bufferedReader().use { it.readText() }
                val og = parseOgImage(html) ?: return@runCatching null

                MediaInfo(
                    sourceUrl = pageUrl,
                    title = og.title ?: "Instagram photo",
                    host = "instagram.com",
                    provider = MediaProvider.INSTAGRAM,
                    mimeType = "image/jpeg",
                    thumbnailUrl = og.imageUrl,
                    isDirectFile = true,
                    downloadUrl = og.imageUrl,
                    variants = listOf(
                        MediaVariant(
                            downloadUrl = og.imageUrl,
                            mimeType = "image/jpeg",
                        ),
                    ),
                )
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }

    internal data class OgMedia(val imageUrl: String, val title: String?)

    /**
     * Pure parse (unit-tested): og:image first, og:title optionally. Only
     * https image URLs are accepted — og:image is a CDN address, and an
     * http or non-http value would fail the destination policy later anyway.
     */
    internal fun parseOgImage(html: String): OgMedia? {
        val propertyFirst = Regex(
            """<meta[^>]+property=["']og:image["'][^>]*content=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE,
        ).find(html)?.groupValues?.get(1)
        val contentFirst = Regex(
            """<meta[^>]+content=["']([^"']+)["'][^>]*property=["']og:image["']""",
            RegexOption.IGNORE_CASE,
        ).find(html)?.groupValues?.get(1)
        val raw = propertyFirst ?: contentFirst ?: return null
        val imageUrl = decodeEntities(raw)
        if (!imageUrl.startsWith("https://")) return null
        val title = Regex(
            """<meta[^>]+property=["']og:title["'][^>]*content=["']([^"']*)["']""",
            RegexOption.IGNORE_CASE,
        ).find(html)?.groupValues?.get(1)?.let(::decodeEntities)?.takeIf { it.isNotBlank() }
        return OgMedia(imageUrl, title)
    }

    private fun decodeEntities(value: String): String = value
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")

    /**
     * Netscape cookies.txt → "name=value; …" header for instagram.com only.
     * The cookies file is app-private; nothing is logged or echoed.
     */
    private fun instagramCookieHeader(): String? = runCatching {
        val file = YtDlpCookies.file(context)
        if (!file.exists()) return@runCatching null
        val pairs = file.readLines().mapNotNull { line ->
            if (line.isBlank() || line.startsWith("#")) return@mapNotNull null
            val parts = line.split('\t')
            if (parts.size < 7) return@mapNotNull null
            val domain = parts[0].lowercase()
            if (domain != "instagram.com" && !domain.endsWith(".instagram.com")) return@mapNotNull null
            "${parts[5]}=${parts[6]}"
        }
        pairs.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }.getOrNull()

    private companion object {
        const val TIMEOUT_MS = 15_000
    }
}
