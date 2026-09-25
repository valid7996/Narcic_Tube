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
        } catch (e: MediaResolveException) {
            // Instagram PHOTO posts: yt-dlp finds no video stream in them
            // (NO_MEDIA) — the og:image fallback resolves the real photo.
            val noMediaPhotoCandidate = e is MediaResolveException.ExtractionFailed &&
                e.provider == MediaProvider.INSTAGRAM &&
                e.reason == MediaResolveException.ExtractionFailed.Reason.NO_MEDIA
            if (noMediaPhotoCandidate) {
                val photo = instagramPhotoResolver.resolvePhotoPost(pageUrl)
                if (photo != null) return Result.success(photo)
            }
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(MediaResolveException.ExtractionFailed(provider, YtDlpErrors.reasonOf(e)))
        }
    }
}
