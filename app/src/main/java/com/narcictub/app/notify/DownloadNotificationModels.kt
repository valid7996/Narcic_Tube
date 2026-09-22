package com.narcictub.app.notify

import com.narcictub.app.domain.model.DownloadProgress
import com.narcictub.app.domain.model.DownloadStatus
import com.narcictub.app.domain.model.HistoryItem

/**
 * PHASE 21 — pure notification model for background download progress.
 *
 * Everything here is deterministic and JVM-testable: the Android-specific
 * NotificationManager glue lives in [DownloadNotificationController].
 *
 * HONESTY CONTRACT: progress comes ONLY from the real downloader bytes
 * ([DownloadProgress]); a total of 100% is reported ONLY for COMPLETED
 * rows; an unknown total renders as INDETERMINATE, never an invented
 * percentage. No URLs, paths, tokens or query strings ever enter a
 * snapshot — the title is the already-sanitized history display title.
 */
data class DownloadNotificationSnapshot(
    /** Persistent history id — the stable identity for the notification. */
    val id: Long,
    val status: DownloadStatus,
    /** Already-sanitized display title (FileNameSanitizer applied at enqueue). */
    val title: String,
    /** Human-readable, safe status line ("Downloading — 42% · clip.mp4"). */
    val text: String,
    /** 0..99 while active, 100 only on COMPLETED; null = indeterminate. */
    val progressPercent: Int?,
    val downloadedBytes: Long,
    /** True only while the row is queued/active (ongoing notification). */
    val ongoing: Boolean,
)

/** What the controller should do with a snapshot for a given id. */
enum class NotificationAction { POST_OR_UPDATE, SKIP, DISMISS }

object DownloadNotifications {

    /** Stable channel for ALL download notifications — created idempotently. */
    const val CHANNEL_ID = "downloads"

    private const val APP_NAME = "NarcicTub"

    /**
     * Builds the snapshot for one history row. Returns null when the row
     * must not render (never happens today; kept for future filtering).
     */
    fun build(id: Long, item: HistoryItem, progress: DownloadProgress?): DownloadNotificationSnapshot? {
        val name = item.title.ifBlank { "Download" }
        val (text, percent, ongoing) = when (item.status) {
            DownloadStatus.QUEUED -> Triple("Queued — $name", null, true)
            DownloadStatus.DOWNLOADING -> {
                val p = progress
                if (p != null && p.totalBytes != null && p.totalBytes > 0) {
                    // Real bytes only; 100 is reserved for the actual
                    // COMPLETED transition — never reported early. Double
                    // math keeps hostile totals (Long.MAX_VALUE) from
                    // overflowing the multiplication.
                    val percent = (p.downloadedBytes.toDouble() / p.totalBytes * 100)
                        .coerceIn(0.0, 99.0).toInt()
                    Triple("Downloading — $percent% — $name", percent, true)
                } else {
                    // Unknown total: honest indeterminate + real bytes.
                    Triple("Downloading — ${humanBytes(p?.downloadedBytes ?: 0)} — $name", null, true)
                }
            }
            DownloadStatus.PAUSED -> Triple("Paused — $name", null, true)
            DownloadStatus.COMPLETED -> Triple("Download complete — $name", 100, false)
            DownloadStatus.FAILED -> Triple("Download failed — $name", null, false)
            DownloadStatus.CANCELLED -> Triple("Download cancelled — $name", null, false)
        }
        return DownloadNotificationSnapshot(
            id = id,
            status = item.status,
            title = APP_NAME,
            text = text,
            progressPercent = percent,
            downloadedBytes = progress?.downloadedBytes ?: 0L,
            ongoing = ongoing,
        )
    }

    /**
     * Throttling rule (Phase 21 §9): notify on the first sight of a row,
     * on any STATUS change, on a ≥5-percentage-point movement, or — for
     * indeterminate transfers — on a ≥1 MB movement of real bytes. The
     * underlying progress stays accurate; only notification refreshes are
     * coalesced.
     */
    fun shouldNotify(previous: DownloadNotificationSnapshot?, next: DownloadNotificationSnapshot): Boolean {
        if (previous == null) return true
        if (previous.status != next.status) return true
        if (previous.progressPercent != null && next.progressPercent != null) {
            return previous.progressPercent / 5 != next.progressPercent / 5
        }
        // Indeterminate vs indeterminate (or crossing the boundary).
        return previous.downloadedBytes / (1024L * 1024L) != next.downloadedBytes / (1024L * 1024L)
    }

    /**
     * Baseline rule (Phase 21 §16): after a process restart, already-
     * terminal rows (completed/failed/cancelled from a previous session)
     * are recorded but NOT re-posted — no completion spam on launch. Active
     * rows always (re-)post their ongoing notification.
     */
    fun decide(seenBefore: Boolean, snapshot: DownloadNotificationSnapshot): NotificationAction {
        if (!seenBefore && snapshot.status == DownloadStatus.COMPLETED) return NotificationAction.SKIP
        if (!seenBefore && snapshot.status == DownloadStatus.FAILED) return NotificationAction.SKIP
        if (!seenBefore && snapshot.status == DownloadStatus.CANCELLED) return NotificationAction.SKIP
        return NotificationAction.POST_OR_UPDATE
    }

    private fun humanBytes(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes / 1e9)
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes / 1e6)
        bytes >= 1L shl 10 -> "%.0f KB".format(bytes / 1e3)
        else -> "$bytes B"
    }
}
