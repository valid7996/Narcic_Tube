package com.narcictub.app.data.resolver

import com.narcictub.app.domain.model.MediaInfo
import com.narcictub.app.domain.model.MediaProvider
import com.narcictub.app.domain.resolver.MediaExtractor
import com.narcictub.app.domain.resolver.MediaResolveException
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PHASE 19 — Instagram extractor: honest, production-grade provider intake
 * with ZERO network activity.
 *
 * WHY THERE IS NO NETWORK EXTRACTION (deliberate design decision, spec §25):
 * retrieving Instagram media metadata through a legitimate path requires
 * either (a) the authorized Instagram Graph API — which needs registered
 * app credentials and user tokens, an authentication infrastructure
 * NarcicTub deliberately does not have — or (b) bypassing Instagram's
 * access/anti-bot controls, which the project's security rules forbid
 * outright. Rather than fabricate metadata or pretend support, this
 * extractor performs the parts that ARE legitimate and safe:
 *
 *  1. STRICT INDEPENDENT HOST VALIDATION — never trusts the detector:
 *     only https/http URLs on instagram.com and its real subdomains are
 *     claimed. Lookalikes (instagram.com.evil.com, evilinstagram.com),
 *     userinfo URLs, dangerous schemes, malformed input, and IP literals
 *     are rejected. (An IP literal can never pass the instagram.com host
 *     check, so localhost/private/loopback targets are excluded by
 *     construction.)
 *  2. CONTENT-TYPE CLASSIFICATION — public media URL shapes
 *     (/p/, /reel/, /reels/, /tv/) are RECOGNIZED and routed; everything
 *     else (profiles, stories, explore, DMs, root) is not media content.
 *  3. HONEST TYPED FAILURES — recognized public media fails with
 *     [MediaResolveException.ExtractionUnavailable]; non-content URLs fail
 *     with [MediaResolveException.UnsupportedSource]. Nothing is ever
 *     fabricated: no fake titles, durations, thumbnails or download URLs.
 *
 * The class holds no network dependencies at all, so it is structurally
 * incapable of making a request — provable by construction and by tests.
 * When an authorized metadata path is added in a future phase, it plugs in
 * here behind the same contract.
 *
 * SUPPORTED CONTENT DECLARATION (spec §3): NONE is downloadable today.
 * Recognized-but-unavailable: public posts, reels, IGTV.
 * Unsupported: profiles, stories (login-only), explore, DMs, root.
 */
@Singleton
class InstagramExtractor @Inject constructor() : MediaExtractor {

    /** Provider-specific extractors outrank the direct-file fallback (0). */
    override val priority: Int = 100

    /**
     * Claims a URL only when it is a structurally valid Instagram URL.
     * This is a ROUTING decision — it never implies extraction support.
     */
    override fun supports(url: String): Boolean = instagramHostOf(url) != null

    override suspend fun extract(url: String): Result<MediaInfo> {
        // Defense in depth: re-validate independently even if the registry
        // routed us here (supports() must never be the security boundary).
        if (instagramHostOf(url) == null) {
            return Result.failure(MediaResolveException.UnsupportedSource())
        }

        return when (classify(url)) {
            is InstagramContent.PublicMedia ->
                Result.failure(MediaResolveException.ExtractionUnavailable(MediaProvider.INSTAGRAM))
            InstagramContent.NotMediaContent ->
                Result.failure(MediaResolveException.UnsupportedSource())
        }
    }

    // ===== independent host validation =====

    /**
     * Returns the lowercase Instagram host when [url] is a structurally
     * valid Instagram URL, or null when anything about it is off: malformed
     * input, non-http(s) scheme, embedded userinfo, or a host that is not
     * instagram.com / a genuine *.instagram.com subdomain. Lookalikes such
     * as `instagram.com.evil.com` and `evilinstagram.com` cannot pass
     * (they neither equal nor end with `.instagram.com`), and neither can
     * IP literals — localhost/loopback/private targets included.
     */
    internal fun instagramHostOf(url: String): String? {
        val uri = try {
            URI(url)
        } catch (_: Exception) {
            return null // malformed URL
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") return null // dangerous schemes rejected
        if (!uri.userInfo.isNullOrEmpty()) return null // embedded credentials rejected
        val host = uri.host?.lowercase() ?: return null // missing/malformed host
        return host.takeIf { it == "instagram.com" || it.endsWith(".instagram.com") }
    }

    // ===== content-type classification =====

    internal sealed interface InstagramContent {
        /**
         * A public media URL shape (/p/, /reel/, /reels/, /tv/) with its
         * shortcode. Recognition only — extraction is unavailable (see the
         * class KDoc).
         */
        data class PublicMedia(val shortcode: String) : InstagramContent

        /** Not a media item: profiles, stories, explore, DMs, root, junk. */
        data object NotMediaContent : InstagramContent
    }

    internal fun classify(url: String): InstagramContent {
        val path = try {
            URI(url).path ?: ""
        } catch (_: Exception) {
            return InstagramContent.NotMediaContent
        }
        val match = MEDIA_PATH.matchEntire(path) ?: return InstagramContent.NotMediaContent
        return InstagramContent.PublicMedia(match.groupValues[1])
    }

    private companion object {
        /** /p/<shortcode>, /reel/<shortcode>, /reels/<shortcode>, /tv/<shortcode>. */
        val MEDIA_PATH = Regex("""^/(?:p|reel|reels|tv)/([A-Za-z0-9_-]+)/?$""")
    }
}
