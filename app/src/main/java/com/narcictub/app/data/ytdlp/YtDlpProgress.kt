package com.narcictub.app.data.ytdlp

import com.narcictub.app.domain.model.DownloadProgress

/**
 * Maps a yt-dlp progress line (`[download]  45.2% of  123.45MiB at ...`) to
 * the app's byte-based [DownloadProgress]. The percentage and the size both
 * come straight from yt-dlp; when the size is not (yet) known the result is
 * INDETERMINATE (total = null) — a percentage is never invented.
 */
internal object YtDlpProgress {

    private val SIZE = Regex("""of\s+~?\s*([0-9]+(?:\.[0-9]+)?)\s*([KMGT]?)iB""")

    /** Null when [percent] is not a real progress value (yt-dlp reports -1 for other lines). */
    fun from(percent: Float, line: String): DownloadProgress? {
        if (percent.isNaN() || percent < 0f) return null
        val clamped = percent.coerceIn(0f, 100f)
        val match = SIZE.find(line)
        val value = match?.groupValues?.get(1)?.toDoubleOrNull()
        if (match == null || value == null) return DownloadProgress(downloadedBytes = 0L, totalBytes = null)
        val multiplier = when (match.groupValues[2]) {
            "K" -> 1024.0
            "M" -> 1024.0 * 1024.0
            "G" -> 1024.0 * 1024.0 * 1024.0
            "T" -> 1024.0 * 1024.0 * 1024.0 * 1024.0
            else -> 1.0
        }
        val total = (value * multiplier).toLong()
        if (total <= 0L) return DownloadProgress(downloadedBytes = 0L, totalBytes = null)
        return DownloadProgress(
            downloadedBytes = (total * (clamped / 100.0)).toLong(),
            totalBytes = total,
        )
    }
}
