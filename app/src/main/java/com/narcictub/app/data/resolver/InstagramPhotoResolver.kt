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

    /**
     * Tries, in order:
     *  1. the post page itself (og:image — works for public posts)
     *  2. the /embed/captioned/ page (historically exposes the photo without
     *     any login, as an EmbeddedMediaImage <img>)
     * The first hit wins; total failure → null (original error surfaces).
     */
    suspend fun resolvePhotoPost(pageUrl: String): MediaInfo? = withContext(Dispatchers.IO) {
        val candidates = buildList {
            add(pageUrl)
            canonicalPostUrl(pageUrl)?.let { add(it + "embed/captioned/") }
        }.distinct()

        var result: MediaInfo? = null
        for (url in candidates) {
            if (result != null) break
            result = runCatching {
                fetchHtml(url)?.let { html ->
                    (parseOgImage(html) ?: parseEmbedImage(html))?.let { buildMediaInfo(pageUrl, it) }
                }
            }.getOrNull()
        }
        result
    }

    /**
     * Same no-login technique as [resolvePhotoPost], but for the VIDEO case:
     * public Reels/videos expose their real CDN mp4 URL either as
     * `og:video` / `og:video:secure_url` meta tags on the post page, or as a
     * `<video src="...">` element on the /embed/captioned/ page — Instagram
     * serves both to make link-preview and website-embed rendering work
     * without a session. This is the same public surface third-party
     * Instagram downloaders read; nothing here is a login bypass, and any
     * post that genuinely needs a session (private/removed) still yields
     * null so the original typed error surfaces unchanged.
     */
    suspend fun resolveVideoPost(pageUrl: String): MediaInfo? = withContext(Dispatchers.IO) {
        val candidates = buildList {
            add(pageUrl)
            canonicalPostUrl(pageUrl)?.let { add(it + "embed/captioned/") }
        }.distinct()

        var result: MediaInfo? = null
        for (url in candidates) {
            if (result != null) break
            result = runCatching {
                fetchHtml(url)?.let { html ->
                    (parseOgVideo(html) ?: parseEmbedVideo(html))?.let { buildVideoMediaInfo(pageUrl, it) }
                }
            }.getOrNull()
        }
        result
    }

    /**
     * Combined fallback used by [com.narcictub.app.data.ytdlp.YtDlpExtractor]
     * once yt-dlp itself fails on an Instagram URL: try the video reading
     * first (a failed post is far more often a gated Reel/video than a
     * photo), then the photo reading. First hit wins; null means both the
     * primary extractor and both fallbacks agree the post needs a login.
     */
    suspend fun resolveFallbackMedia(pageUrl: String): MediaInfo? =
        resolveVideoPost(pageUrl) ?: resolvePhotoPost(pageUrl)

    private fun fetchHtml(url: String): String? {
        val connection = URL(url).openConnection() as HttpsURLConnection
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
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun buildMediaInfo(pageUrl: String, og: OgMedia): MediaInfo = MediaInfo(
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

    private fun buildVideoMediaInfo(pageUrl: String, og: OgVideo): MediaInfo = MediaInfo(
        sourceUrl = pageUrl,
        title = og.title ?: "Instagram video",
        host = "instagram.com",
        provider = MediaProvider.INSTAGRAM,
        mimeType = "video/mp4",
        thumbnailUrl = og.thumbnailUrl,
        isDirectFile = true,
        downloadUrl = og.videoUrl,
        variants = listOf(
            MediaVariant(
                downloadUrl = og.videoUrl,
                mimeType = "video/mp4",
            ),
        ),
    )

    /** کد پست را از هر شکلی از لینک اینستاگرام بیرون می‌کشد. */
    internal fun canonicalPostUrl(pageUrl: String): String? {
        val match = Regex("""instagram\.com/(p|reel|reels|tv)/([A-Za-z0-9_-]+)""").find(pageUrl) ?: return null
        val type = if (match.groupValues[1] == "p") "p" else "reel"
        return "https://www.instagram.com/$type/${match.groupValues[2]}/"
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

    /** صفحه embed اینستاگرام عکس را در <img class="EmbeddedMediaImage"> دارد. */
    internal fun parseEmbedImage(html: String): OgMedia? {
        val raw = Regex(
            """EmbeddedMediaImage[^>]*src=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE,
        ).find(html)?.groupValues?.get(1) ?: return null
        val imageUrl = decodeEntities(raw)
        if (!imageUrl.startsWith("https://")) return null
        return OgMedia(imageUrl, null)
    }

    internal data class OgVideo(val videoUrl: String, val title: String?, val thumbnailUrl: String? = null)

    /**
     * Pure parse (unit-tested): `og:video:secure_url` is preferred (it is
     * always https on Instagram); plain `og:video` is the fallback and is
     * only accepted when it is itself https, since some pages emit an http
     * copy alongside the secure one. `og:image` doubles as the thumbnail
     * when present. Attribute order (property-first vs content-first) is
     * handled the same way as [parseOgImage].
     */
    internal fun parseOgVideo(html: String): OgVideo? {
        fun metaContent(property: String): String? {
            val propertyFirst = Regex(
                """<meta[^>]+property=["']$property["'][^>]*content=["']([^"']+)["']""",
                RegexOption.IGNORE_CASE,
            ).find(html)?.groupValues?.get(1)
            val contentFirst = Regex(
                """<meta[^>]+content=["']([^"']+)["'][^>]*property=["']$property["']""",
                RegexOption.IGNORE_CASE,
            ).find(html)?.groupValues?.get(1)
            return (propertyFirst ?: contentFirst)?.let(::decodeEntities)
        }

        val secureUrl = metaContent("og:video:secure_url")?.takeIf { it.startsWith("https://") }
        val plainUrl = metaContent("og:video")?.takeIf { it.startsWith("https://") }
        val videoUrl = secureUrl ?: plainUrl ?: return null
        val title = metaContent("og:title")?.takeIf { it.isNotBlank() }
        val thumbnailUrl = metaContent("og:image")?.takeIf { it.startsWith("https://") }
        return OgVideo(videoUrl, title, thumbnailUrl)
    }

    /**
     * صفحه embed اینستاگرام برای ویدیو/ریلز معمولاً یک تگ
     * <video src="..."> عمومی دارد که بدون لاگین بارگذاری می‌شود.
     */
    internal fun parseEmbedVideo(html: String): OgVideo? {
        val raw = Regex(
            """<video[^>]+src=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE,
        ).find(html)?.groupValues?.get(1) ?: return null
        val videoUrl = decodeEntities(raw)
        if (!videoUrl.startsWith("https://")) return null
        val poster = Regex(
            """<video[^>]+poster=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE,
        ).find(html)?.groupValues?.get(1)?.let(::decodeEntities)?.takeIf { it.startsWith("https://") }
        return OgVideo(videoUrl, null, poster)
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
