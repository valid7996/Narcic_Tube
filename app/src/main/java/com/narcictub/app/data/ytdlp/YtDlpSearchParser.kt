package com.narcictub.app.data.ytdlp

import com.narcictub.app.domain.model.YoutubeSearchItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Pure translation of `yt-dlp --flat-playlist --dump-single-json
 * "ytsearchN:query"` into search items. No Android, no network — fully
 * unit-testable. Fields are real (title/url/duration/views come from
 * YouTube); unknown stays null, never invented.
 */
internal object YtDlpSearchParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(raw: String): List<YoutubeSearchItem> {
        val root = runCatching {
            val start = raw.indexOf('{')
            val end = raw.lastIndexOf('}')
            if (start < 0 || end <= start) return emptyList()
            json.parseToJsonElement(raw.substring(start, end + 1)).jsonObject
        }.getOrNull() ?: return emptyList()

        val entries = root["entries"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        return entries.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val id = item.str("id") ?: return@mapNotNull null
            val url = item.str("url") ?: "https://www.youtube.com/watch?v=$id"
            val title = item.str("title") ?: return@mapNotNull null
            if (item["is_live"]?.jsonPrimitive?.booleanOrNull == true) return@mapNotNull null
            YoutubeSearchItem(
                id = id,
                title = title,
                channel = item.str("channel") ?: item.str("uploader"),
                durationSeconds = item.number("duration")?.toLong()?.takeIf { it > 0 },
                viewCount = item.number("view_count")?.toLong(),
                thumbnailUrl = item.str("thumbnail")
                    ?: ((item["thumbnails"] as? kotlinx.serialization.json.JsonArray)
                        ?.lastOrNull()?.jsonObject?.str("url")),
                watchUrl = url,
            )
        }.take(15)
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
            ?: (this[key] as? JsonPrimitive)?.jsonPrimitive?.let { it.content.takeIf { c -> c.isNotBlank() } }

    private fun JsonObject.number(key: String): Double? =
        (this[key] as? JsonPrimitive)?.doubleOrNull
}
