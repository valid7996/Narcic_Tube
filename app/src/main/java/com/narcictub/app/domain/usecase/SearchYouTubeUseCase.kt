package com.narcictub.app.domain.usecase

import com.narcictub.app.data.ytdlp.YtDlpEngine
import com.narcictub.app.domain.model.YoutubeSearchItem
import javax.inject.Inject

/**
 * YouTube search through the bundled yt-dlp engine (`ytsearchN:` — the same
 * engine used for extraction, so no API key, no extra network stack).
 * Blank queries are rejected; engine failures map to a Result.
 */
class SearchYouTubeUseCase @Inject constructor(
    private val engine: YtDlpEngine,
) {
    suspend operator fun invoke(query: String, limit: Int = 15): Result<List<YoutubeSearchItem>> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return Result.failure(IllegalArgumentException("Empty search"))
        return runCatching { engine.search(trimmed, limit.coerceIn(1, 20)) }
    }
}
