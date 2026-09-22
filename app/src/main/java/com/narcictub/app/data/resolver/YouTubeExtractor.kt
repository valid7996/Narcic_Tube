package com.narcictub.app.data.resolver

import com.narcictub.app.domain.model.MediaInfo
import com.narcictub.app.domain.model.MediaProvider
import com.narcictub.app.domain.resolver.MediaExtractor
import com.narcictub.app.domain.resolver.MediaResolveException
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PHASE 24 — YouTube intake: the Instagram-pattern treatment (strict
 * independent host validation + content classification + honest typed
 * failures) applied to the YouTube verdict of the Phase 24 feasibility
 * review.
 *
 * FEASIBILITY VERDICT (documented, no fake support):
 *  - The official YouTube Data API v3 provides metadata (title, duration,
 *    thumbnails) but NO downloadable media stream URLs; downloading YouTube
 *    media is prohibited by YouTube's Terms of Service. There is therefore
 *    no legitimate API path to a genuinely usable MediaVariant.
 *  - The only mechanisms that produce stream URLs are reverse-engineered
 *    scrapers (e.g. NewPipe Extractor) that operate by defeating YouTube's
 *    signature/anti-bot protections — explicitly forbidden by this
 *    project's rules (anti-bot/access-control bypass). Supply-chain review
 *    also REJECTS them: GPL-3.0, no stable published Android artifact,
 *    uncontrolled network behavior, constant churn against platform
 *    countermeasures, and not resolvable in this environment.
 *  => Recognized public-media URLs fail with
 *     [MediaResolveException.ExtractionUnavailable]; nothing is fabricated.
 *
 * Like [InstagramExtractor], this class injects NO network dependencies —
 * it is structurally incapable of making requests.
 *
 * RECOGNIZED content shapes: /watch?v=<id>, /shorts/<id>, youtu.be/<id>.
 * Everything else (channels, playlists, /embed, /live without id, root) is
 * not a media item and yields UnsupportedSource.
 */
@Singleton
class YouTubeExtractor @Inject constructor() : MediaExtractor {

    /** Provider-specific extractors outrank the direct-file fallback (0). */
    override val priority: Int = 100

    /** Routing decision only — never implies extraction support. */
    override fun supports(url: String): Boolean = youTubeHostOf(url) != null

    override suspend fun extract(url: String): Result<MediaInfo> {
        // Defense in depth: never trust the registry's routing decision.
        if (youTubeHostOf(url) == null) {
            return Result.failure(MediaResolveException.UnsupportedSource())
        }

        return when (classify(url)) {
            is YouTubeContent.PublicMedia ->
                Result.failure(MediaResolveException.ExtractionUnavailable(MediaProvider.YOUTUBE))
            YouTubeContent.NotMediaContent ->
                Result.failure(MediaResolveException.UnsupportedSource())
        }
    }

    // ===== independent host validation =====

    /**
     * Lowercase YouTube host when [url] is a structurally valid YouTube
     * URL; null otherwise (malformed input, non-http(s), userinfo, IP
     * literals, and lookalikes — only youtube.com/subdomains and youtu.be
     * pass; `youtube.com.evil.com` and `notyoutube.com` cannot).
     */
    internal fun youTubeHostOf(url: String): String? {
        val uri = try {
            URI(url)
        } catch (_: Exception) {
            return null
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") return null
        if (!uri.userInfo.isNullOrEmpty()) return null
        val host = uri.host?.lowercase() ?: return null
        return host.takeIf {
            it == "youtube.com" || it == "youtu.be" || it.endsWith(".youtube.com")
        }
    }

    // ===== content-type classification =====

    internal sealed interface YouTubeContent {
        /** Recognized public media URL shape — recognition is not support. */
        data class PublicMedia(val videoId: String) : YouTubeContent

        /** Not a media item: channels, playlists, root, unknown shapes. */
        data object NotMediaContent : YouTubeContent
    }

    internal fun classify(url: String): YouTubeContent {
        val uri = try {
            URI(url)
        } catch (_: Exception) {
            return YouTubeContent.NotMediaContent
        }
        val host = uri.host?.lowercase() ?: return YouTubeContent.NotMediaContent
        val path = uri.path ?: ""

        // youtu.be/<id> short links.
        if (host == "youtu.be") {
            val id = path.removePrefix("/")
            return if (id.matches(SHORTCODE)) {
                YouTubeContent.PublicMedia(id)
            } else {
                YouTubeContent.NotMediaContent
            }
        }

        // /shorts/<id> on the main hosts.
        val shorts = Regex("""^/shorts/([A-Za-z0-9_-]+)/?$""").matchEntire(path)
        if (shorts != null) return YouTubeContent.PublicMedia(shorts.groupValues[1])

        // /watch?v=<id> — the id must be a real shortcode in the query.
        if (path == "/watch") {
            val id = uri.rawQuery
                ?.split('&')
                ?.firstOrNull { it.startsWith("v=") }
                ?.substring(2)
            if (id != null && id.matches(SHORTCODE)) return YouTubeContent.PublicMedia(id)
        }
        return YouTubeContent.NotMediaContent
    }

    private companion object {
        val SHORTCODE = Regex("""[A-Za-z0-9_-]{6,}""")
    }
}
