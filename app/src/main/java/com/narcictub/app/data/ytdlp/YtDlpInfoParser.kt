package com.narcictub.app.data.ytdlp

import com.narcictub.app.domain.model.MediaInfo
import com.narcictub.app.domain.model.MediaProvider
import com.narcictub.app.domain.model.MediaVariant
import com.narcictub.app.domain.resolver.MediaResolveException
import com.narcictub.app.domain.resolver.MediaResolveException.ExtractionFailed.Reason
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import java.net.URI

/**
 * Pure translation of `yt-dlp --dump-single-json` output into [MediaInfo].
 * No Android, no network — fully unit-testable.
 *
 * HONESTY CONTRACT (same as the direct-file resolver): every field comes from
 * the JSON yt-dlp returned. Unknown stays null; nothing is guessed.
 *
 * VARIANT POLICY — what the user can pick, one entry per resolution:
 *  1. Muxed formats (video+audio in one file, e.g. YouTube 360p): single file,
 *     no ffmpeg involved — the most robust choice, so it wins its height.
 *  2. For every other height: the best video-only stream (H.264/mp4 preferred,
 *     so the result plays everywhere) paired with a container-compatible audio
 *     stream (m4a for mp4, webm/opus for webm); yt-dlp + ffmpeg merge them.
 *  3. One audio-only entry (m4a preferred).
 * Live-storyboard/mhtml formats are ignored, and HLS (m3u8) formats are only
 * used when nothing else exists.
 */
internal object YtDlpInfoParser {

    private const val MAX_VIDEO_VARIANTS = 10

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parse(pageUrl: String, provider: MediaProvider, raw: String): MediaInfo {
        val root = parseRoot(provider, raw)
        val item = firstItem(root)

        val id = item.str("id")
        val title = item.str("title") ?: root.str("title") ?: item.str("fulltitle")
        val duration = item.number("duration")?.toLong()?.takeIf { it > 0L }
        val thumbnail = item.str("thumbnail")
            ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }

        val variants = buildVariants(pageUrl, item, duration)
        if (variants.isEmpty()) {
            throw MediaResolveException.ExtractionFailed(provider, Reason.NO_MEDIA)
        }

        val host = try {
            URI(pageUrl).host?.lowercase()
        } catch (_: Exception) {
            null
        } ?: provider.displayName

        return MediaInfo(
            sourceUrl = pageUrl,
            title = title ?: id?.let { "${provider.displayName} $it" },
            host = host,
            provider = provider,
            durationSeconds = duration,
            thumbnailUrl = thumbnail,
            variants = variants,
            isDirectFile = false,
        )
    }

    // ===== JSON access =====

    private fun parseRoot(provider: MediaProvider, raw: String): JsonObject {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) {
            throw MediaResolveException.ExtractionFailed(provider, Reason.NO_MEDIA)
        }
        return try {
            json.parseToJsonElement(raw.substring(start, end + 1)).jsonObject
        } catch (_: Exception) {
            throw MediaResolveException.ExtractionFailed(provider, Reason.OTHER)
        }
    }

    /** Instagram carousels come back as a playlist; the first entry is the item. */
    private fun firstItem(root: JsonObject): JsonObject {
        val entries = root["entries"] as? JsonArray ?: return root
        return entries.firstOrNull { it is JsonObject } as? JsonObject ?: root
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    private fun JsonObject.number(key: String): Double? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

    // ===== formats =====

    private class Format(
        val id: String,
        val ext: String?,
        val vcodec: String?,
        val acodec: String?,
        val width: Int?,
        val height: Int?,
        val size: Long?,
        val bitrate: Double?,
        val protocol: String?,
    ) {
        val isAudioOnly: Boolean get() = vcodec == "none" && acodec != "none"
        val isVideoOnly: Boolean get() = acodec == "none" && vcodec != "none"
        val isMuxed: Boolean get() = vcodec != "none" && acodec != "none"

        /** Ranking weight: real bitrate, else real size, else 0. */
        val score: Double get() = bitrate ?: (size?.toDouble() ?: 0.0)
    }

    private fun readFormats(item: JsonObject): List<Format> {
        val array = item["formats"] as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val f = element as? JsonObject ?: return@mapNotNull null
            val id = f.str("format_id") ?: return@mapNotNull null
            if (!YtDlpUrl.isSafeFormatId(id)) return@mapNotNull null
            val protocol = f.str("protocol")
            val ext = f.str("ext")?.lowercase()
            if (ext == "mhtml" || protocol?.startsWith("mhtml") == true) return@mapNotNull null
            val vcodec = f.str("vcodec")
            val acodec = f.str("acodec")
            if (vcodec == "none" && acodec == "none") return@mapNotNull null // storyboards etc.
            Format(
                id = id,
                ext = ext,
                vcodec = vcodec,
                acodec = acodec,
                width = f.number("width")?.toInt(),
                height = f.number("height")?.toInt(),
                size = (f.number("filesize") ?: f.number("filesize_approx"))?.toLong(),
                bitrate = f.number("tbr") ?: f.number("abr") ?: f.number("vbr"),
                protocol = protocol,
            )
        }
    }

    private fun buildVariants(pageUrl: String, item: JsonObject, duration: Long?): List<MediaVariant> {
        val all = readFormats(item)
        val usable = all.filter { it.protocol?.contains("m3u8") != true }.ifEmpty { all }

        if (usable.isEmpty()) return singleUrlFallback(pageUrl, item, duration)

        val muxed = usable.filter { it.isMuxed }
        val videoOnly = usable.filter { it.isVideoOnly }
        val audioOnly = usable.filter { it.isAudioOnly }

        val video = mutableListOf<MediaVariant>()
        val coveredHeights = mutableSetOf<Int?>()

        // 1. Muxed: one per height, best bitrate.
        muxed.groupBy { it.height }.forEach { (height, group) ->
            val best = group.maxByOrNull { it.score } ?: return@forEach
            coveredHeights.add(height)
            video += MediaVariant(
                downloadUrl = YtDlpUrl.withFormat(pageUrl, best.id),
                mimeType = YtDlpMime.video(best.ext),
                container = best.ext,
                width = best.width,
                height = height,
                sizeBytes = best.size,
                durationSeconds = duration,
                qualityLabel = height?.let { "${it}p" },
            )
        }

        // 2. Video-only + audio-only pairs for the remaining heights.
        videoOnly.groupBy { it.height }.forEach { (height, group) ->
            if (height == null || height in coveredHeights) return@forEach
            val bestVideo = group.filter { it.ext == "mp4" }.maxByOrNull { it.score }
                ?: group.maxByOrNull { it.score }
                ?: return@forEach
            val bestAudio = pickAudioFor(bestVideo, audioOnly) ?: return@forEach
            val container = mergedContainer(bestVideo.ext, bestAudio.ext)
            video += MediaVariant(
                downloadUrl = YtDlpUrl.withFormat(pageUrl, "${bestVideo.id}+${bestAudio.id}"),
                mimeType = YtDlpMime.video(container),
                container = container,
                width = bestVideo.width,
                height = height,
                sizeBytes = sumOrNull(bestVideo.size, bestAudio.size),
                durationSeconds = duration,
                qualityLabel = "${height}p",
            )
        }

        val ordered = video.sortedByDescending { it.height ?: 0 }.take(MAX_VIDEO_VARIANTS).toMutableList()

        // 3. Audio only (m4a preferred: plays everywhere, needs no re-encode).
        val bestAudio = audioOnly.filter { it.ext == "m4a" }.maxByOrNull { it.score }
            ?: audioOnly.maxByOrNull { it.score }
        if (bestAudio != null) {
            ordered += MediaVariant(
                downloadUrl = YtDlpUrl.withFormat(pageUrl, bestAudio.id),
                mimeType = YtDlpMime.audio(bestAudio.ext),
                container = bestAudio.ext,
                sizeBytes = bestAudio.size,
                durationSeconds = duration,
            )
        }

        return if (ordered.isEmpty()) singleUrlFallback(pageUrl, item, duration) else ordered
    }

    /** Some extractors expose one plain file and no `formats` list. */
    private fun singleUrlFallback(pageUrl: String, item: JsonObject, duration: Long?): List<MediaVariant> {
        val hasFormatList = (item["formats"] as? JsonArray)?.isNotEmpty() == true
        if (hasFormatList || item.str("url") == null) return emptyList()
        val ext = item.str("ext")?.lowercase()
        val height = item.number("height")?.toInt()
        return listOf(
            MediaVariant(
                downloadUrl = YtDlpUrl.withFormat(pageUrl, "best"),
                mimeType = YtDlpMime.video(ext),
                container = ext,
                width = item.number("width")?.toInt(),
                height = height,
                durationSeconds = duration,
                qualityLabel = height?.let { "${it}p" },
            ),
        )
    }

    private fun pickAudioFor(video: Format, audio: List<Format>): Format? {
        if (audio.isEmpty()) return null
        val compatible = when (video.ext) {
            "mp4" -> audio.filter { it.ext == "m4a" || it.ext == "mp4" }
            "webm" -> audio.filter { it.ext == "webm" }
            else -> emptyList()
        }
        return compatible.ifEmpty { audio }.maxByOrNull { it.score }
    }

    /** Mirrors yt-dlp's default merge rule: mp4+m4a → mp4, webm+webm → webm, else mkv. */
    private fun mergedContainer(videoExt: String?, audioExt: String?): String = when {
        videoExt == "mp4" && (audioExt == "m4a" || audioExt == "mp4") -> "mp4"
        videoExt == "webm" && audioExt == "webm" -> "webm"
        else -> "mkv"
    }

    private fun sumOrNull(a: Long?, b: Long?): Long? = if (a != null && b != null) a + b else null
}
