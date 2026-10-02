package com.narcictub.app.data.network

import com.narcictub.app.domain.model.YoutubeSearchItem
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * HONEY — YouTube client (REAL): talks to YouTube's own InnerTube endpoints
 * exactly like the youtube.com web player does —
 *
 *  - SEARCH  → POST /youtubei/v1/search  (WEB client) — real results
 *  - PLAY    → POST /youtubei/v1/player  (ANDROID client) — direct mp4
 *              stream URLs for playback (progressive formats carry both
 *              audio + video, playable by a plain MediaPlayer)
 *
 * Every request goes through [DoHNetwork.client] — when a DoH endpoint is
 * configured in Settings, DNS for youtube.com resolves over HTTPS, which is
 * precisely how the real YouTube site keeps working behind poisoned DNS.
 * No API key of our own, no login, nothing simulated.
 */
@Singleton
class YouTubeClient @Inject constructor(
    private val dohNetwork: DoHNetwork,
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class Stream(val url: String, val title: String?, val description: String? = null)

    /** جستجوی واقعی یوتیوب (InnerTube WEB client). */
    suspend fun search(query: String, limit: Int = 20): List<YoutubeSearchItem> =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("context", WEB_CONTEXT)
                put("query", query.trim())
            }
            val response = post(INNERTUBE_SEARCH, body)
            parseSearch(response, limit)
        }

    /**
     * آدرس استریم مستقیم برای پخش (ANDROID client → فرمت progressive با
     * صدا+تصویر، بدون نیاز به decipher). null = پخش مستقیم ممکن نیست.
     */
    /**
     * آدرس استریم مستقیم برای پخش. چند کلاینت InnerTube را به ترتیب امتحان
     * می‌کند (IOS معمولاً بدون PO-token جواب می‌دهد؛ ANDROID پشتیبان) و
     * بهترین فرمت progressive (صدا+تصویر با هم) را برمی‌گرداند — MediaPlayer
     * می‌تواند آن را مستقیم استریم کند. null = پخش مستقیم ممکن نیست.
     */
    suspend fun resolveStream(watchUrl: String): Stream? = withContext(Dispatchers.IO) {
        val videoId = videoIdOf(watchUrl) ?: return@withContext null
        for (context in listOf(IOS_CONTEXT, ANDROID_CONTEXT)) {
            val body = buildJsonObject {
                put("context", context)
                put("videoId", videoId)
                put("contentCheckOk", true)
                put("racyCheckOk", true)
            }
            val response = postWithUa(INNERTUBE_PLAYER, body, clientUaFor(context))
            val root = runCatching { json.parseToJsonElement(response).jsonObject }.getOrNull()
                ?: continue
            if (root.playabilityStatus() != "OK") continue

            val streaming = root["streamingData"]?.jsonObject ?: continue
            val formats = streaming["formats"] as? JsonArray ?: continue

            // فقط progressive (صدا+تصویر با هم) — MediaPlayer نمی‌تواند DASH جدا پخش کند
            val best = formats.asSequence()
                .mapNotNull { it as? JsonObject }
                .filter { it.str("url") != null }
                .filter { it.str("mimeType")?.contains("mp4") == true }
                .maxByOrNull { it.number("bitrate") ?: 0L }
                ?: continue
            return@withContext Stream(
                url = best.str("url")!!,
                title = root["videoDetails"]?.jsonObject?.str("title"),
                description = root["videoDetails"]?.jsonObject?.str("shortDescription"),
            )
        }
        null
    }

    private fun JsonObject.playabilityStatus(): String? =
        (this["playabilityStatus"]?.jsonObject?.get("status") as? JsonPrimitive)?.content

    private fun clientUaFor(context: JsonObject): String = when {
        context.toString().contains("\"IOS\"") -> IOS_UA
        else -> ANDROID_UA
    }

    // ===== search parsing =====

    private fun parseSearch(raw: String, limit: Int): List<YoutubeSearchItem> {
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: return emptyList()
        val results = mutableListOf<YoutubeSearchItem>()
        walkForVideoRenderers(root) { renderer ->
            if (results.size >= limit) return@walkForVideoRenderers
            val id = renderer.str("videoId") ?: return@walkForVideoRenderers
            val title = renderer.obj("title")?.runsText() ?: return@walkForVideoRenderers
            val channel = renderer.obj("ownerText")?.runsText()
                ?: renderer.obj("longBylineText")?.runsText()
            val durationText = renderer.obj("lengthText")?.str("simpleText")
            val views = renderer.obj("viewCountText")?.str("simpleText")
            val thumb = (renderer.obj("thumbnail")?.obj("thumbnails") as? JsonArray)
                ?.lastOrNull()?.jsonObject?.str("url")
            results += YoutubeSearchItem(
                id = id,
                title = title,
                channel = channel,
                durationSeconds = durationText?.parseHms(),
                viewCount = null,
                thumbnailUrl = thumb,
                watchUrl = "https://www.youtube.com/watch?v=$id",
            )
        }
        return results
    }

    /** هر videoRenderer را در هر عمقی از پاسخ جستجو پیدا می‌کند. */
    private fun walkForVideoRenderers(
        element: kotlinx.serialization.json.JsonElement,
        onFound: (JsonObject) -> Unit,
    ) {
        when (element) {
            is JsonObject -> {
                element["videoRenderer"]?.jsonObject?.let(onFound)
                element.values.forEach { walkForVideoRenderers(it, onFound) }
            }
            is JsonArray -> element.forEach { walkForVideoRenderers(it, onFound) }
            else -> Unit
        }
    }

    // ===== helpers =====

    /**
     * بندانگشتی از مسیر DoH — وقتی DNS سیستم فیلتر باشد کار می‌کند
     * (همان قابلیتی که یوتیوب اصلی دارد).
     */
    suspend fun fetchThumbnail(url: String): android.graphics.Bitmap? =
        withContext(Dispatchers.IO) {
            if (url.isBlank()) return@withContext null
            runCatching {
                val request = Request.Builder().url(url).build()
                dohNetwork.client().newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@runCatching null
                    response.body?.byteStream()?.use { input ->
                        android.graphics.BitmapFactory.decodeStream(input)
                    }
                }
            }.getOrNull()
        }

    private suspend fun post(url: String, body: JsonObject): String =
        postWithUa(url, body, ANDROID_UA)

    /** POST با User-Agent قابل انتخاب (کلاینت IOS/ANDROID UA خودشان را می‌خواهند). */
    private suspend fun postWithUa(url: String, body: JsonObject, userAgent: String): String {
        val client = dohNetwork.client()
        val request = okhttp3.Request.Builder()
            .url(url)
            .post(
                okhttp3.RequestBody.create(
                    "application/json; charset=utf-8".toMediaTypeOrNull(),
                    body.toString(),
                ),
            )
            .header("User-Agent", userAgent)
            .build()
        client.newCall(request).execute().use { response ->
            return response.body?.string()
                ?: throw java.io.IOException("empty response from YouTube")
        }
    }

    private fun videoIdOf(watchUrl: String): String? =
        Regex("""[?&]v=([A-Za-z0-9_-]{6,})""").find(watchUrl)?.groupValues?.get(1)
            ?: Regex("""youtu\.be/([A-Za-z0-9_-]{6,})""").find(watchUrl)?.groupValues?.get(1)

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    private fun JsonObject.number(key: String): Long? =
        (this[key] as? JsonPrimitive)?.content?.toLongOrNull()

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.runsText(): String? =
        (this["runs"] as? JsonArray)?.firstOrNull()?.jsonObject?.str("text")

    private fun String.parseHms(): Long? {
        val parts = split(":").map { it.trim() }
        if (parts.any { it.toIntOrNull() == null }) return null
        return when (parts.size) {
            2 -> parts[0].toInt() * 60L + parts[1].toInt()
            3 -> parts[0].toInt() * 3600L + parts[1].toInt() * 60L + parts[2].toInt()
            else -> null
        }
    }

    private fun String.toMediaTypeOrNull() = this.toMediaType()

    private companion object {
        /** کلید عمومی وب‌کلاینت یوتیوب — همان که youtube.com خودش استفاده می‌کند. */
        const val INNERTUBE_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
        const val INNERTUBE_SEARCH =
            "https://www.youtube.com/youtubei/v1/search?key=$INNERTUBE_KEY&prettyPrint=false"
        const val INNERTUBE_PLAYER =
            "https://www.youtube.com/youtubei/v1/player?key=$INNERTUBE_KEY&prettyPrint=false"
        const val ANDROID_UA = "com.google.android.youtube/19.09.37 (Linux; U; Android 13) gzip"
        const val IOS_UA = "com.google.ios.youtube/19.29.1 (iPhone16,2; U; CPU iOS 17_3 like Mac OS X)"
        private val IOS_CONTEXT = buildJsonObject {
            put(
                "client",
                buildJsonObject {
                    put("clientName", JsonPrimitive("IOS"))
                    put("clientVersion", JsonPrimitive("19.29.1"))
                    put("deviceModel", JsonPrimitive("iPhone16,2"))
                    put("hl", JsonPrimitive("en"))
                },
            )
        }


        private val WEB_CONTEXT = buildJsonObject {
            put(
                "client",
                buildJsonObject {
                    put("clientName", JsonPrimitive("WEB"))
                    put("clientVersion", JsonPrimitive("2.20240726.00.00"))
                    put("hl", JsonPrimitive("en"))
                },
            )
        }

        private val ANDROID_CONTEXT = buildJsonObject {
            put(
                "client",
                buildJsonObject {
                    put("clientName", JsonPrimitive("ANDROID"))
                    put("clientVersion", JsonPrimitive("19.09.37"))
                    put("androidSdkVersion", JsonPrimitive(30))
                    put("hl", JsonPrimitive("en"))
                },
            )
        }
    }
}
