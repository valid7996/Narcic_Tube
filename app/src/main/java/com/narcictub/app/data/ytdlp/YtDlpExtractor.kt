package com.narcictub.app.data.ytdlp

import com.narcictub.app.domain.model.MediaInfo
import com.narcictub.app.domain.model.MediaProvider
import com.narcictub.app.domain.resolver.MediaExtractor
import com.narcictub.app.domain.resolver.MediaResolveException
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * YouTube + Instagram extractor backed by yt-dlp. It replaces the old
 * Instagram-only stub (which deliberately never touched the network) and
 * fills the "YouTube: not implemented" gap.
 *
 * supports() is a ROUTING decision made from an independent strict host check
 * ([YtDlpUrl.providerOf]) — it is never trusted as the security boundary, so
 * extract() validates again. Priority 100 outranks the direct-file fallback.
 */
@Singleton
class YtDlpExtractor @Inject constructor(
    private val engine: YtDlpEngine,
    private val instagramPhotoResolver: com.narcictub.app.data.resolver.InstagramPhotoResolver,
) : MediaExtractor {

    override val priority: Int = 100

    override fun supports(url: String): Boolean =
        YtDlpUrl.providerOf(YtDlpUrl.parse(url).pageUrl) != MediaProvider.UNKNOWN

    override suspend fun extract(url: String): Result<MediaInfo> {
        val pageUrl = YtDlpUrl.parse(url).pageUrl
        val provider = YtDlpUrl.providerOf(pageUrl)
        if (provider == MediaProvider.UNKNOWN) {
            return Result.failure(MediaResolveException.UnsupportedSource())
        }
        return try {
            val raw = engine.dumpJson(pageUrl)
            Result.success(YtDlpInfoParser.parse(pageUrl, provider, raw))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // yt-dlp fails Instagram two different ways, and BOTH land here
            // as plain exceptions, not just the "successful JSON with no
            // video stream" case: most often the request itself is refused
            // before any JSON comes back (login wall / rate-limit / bot
            // check in yt-dlp's stderr — a generic RuntimeException), and
            // less often yt-dlp returns valid JSON with zero formats for a
            // photo post (YtDlpInfoParser throws a typed
            // MediaResolveException then). Either way gets a no-login
            // fallback attempt before giving up: the post's public
            // og:video/og:image metadata (or its /embed/captioned/ page),
            // which Instagram serves unauthenticated for link previews and
            // website embeds. Skipped when the engine itself never started
            // (YtDlpEngineException) — no webpage fetch can fix that.
            if (provider == MediaProvider.INSTAGRAM && e !is YtDlpEngineException) {
                val fallback = instagramPhotoResolver.resolveFallbackMedia(pageUrl)
                if (fallback != null) return Result.success(fallback)
            }
            val typed = e as? MediaResolveException
                ?: MediaResolveException.ExtractionFailed(provider, YtDlpErrors.reasonOf(e))
            Result.failure(typed)
        }
    }
}
