package com.narcictub.app.ui.history

import com.narcictub.app.domain.model.DownloadStatus
import com.narcictub.app.domain.model.DownloadsOverview
import com.narcictub.app.domain.model.HistoryItem
import com.narcictub.app.domain.model.MediaFileAvailability
import java.net.URI

/**
 * PHASE 10 — pure UI-state mapping for the History screen: records grouped
 * by lifecycle (active/queued, completed, failed & cancelled) with honest
 * file availability on completed rows.
 *
 * SECURITY: only the source host is ever exposed — never the full URL, its
 * query strings, or the localUri. Availability is the storage backend's
 * answer, not a guess; un-checked completed rows stay NOT_APPLICABLE.
 */
data class HistoryUiState(
    val active: List<UiHistoryRow> = emptyList(),
    val completed: List<UiHistoryRow> = emptyList(),
    val dismissed: List<UiHistoryRow> = emptyList(),
    val isLoading: Boolean = false,
    /** PHASE 12: the persistence layer failed to deliver data — safe state. */
    val hasError: Boolean = false,
) {
    val isEmpty: Boolean get() = active.isEmpty() && completed.isEmpty() && dismissed.isEmpty()
    val showLoading: Boolean get() = isLoading && isEmpty
    val showError: Boolean get() = hasError && isEmpty

    /** True while failed/cancelled records exist — drives "Clear failed". */
    val hasClearableFailed: Boolean get() = dismissed.isNotEmpty()

    /** True while completed records exist — drives "Clear completed". */
    val hasClearableCompleted: Boolean get() = completed.isNotEmpty()
}

/** One rendered history row — deliberately holds no URL and no localUri. */
data class UiHistoryRow(
    val id: Long,
    val title: String,
    val host: String?,
    val status: DownloadStatus,
    val mimeType: String?,
    /** Real persisted quality label (Phase 12) — null stays unrendered. */
    val quality: String?,
    val sizeBytes: Long,
    /** PHASE 22: real container duration (seconds); null stays unrendered. */
    val durationSeconds: Long?,
    val completedAtEpochMs: Long?,
    val errorMessage: String?,
    val availability: MediaFileAvailability,
)

/** Maps a domain snapshot plus the availability cache into UI rows. */
fun DownloadsOverview.toHistoryUiState(
    availability: Map<Long, MediaFileAvailability>,
): HistoryUiState {
    val active = mutableListOf<UiHistoryRow>()
    val completed = mutableListOf<UiHistoryRow>()
    val dismissed = mutableListOf<UiHistoryRow>()

    for (item in items) {
        val row = item.toUiHistoryRow(availability[item.id] ?: MediaFileAvailability.NOT_APPLICABLE)
        when (item.status) {
            DownloadStatus.QUEUED,
            DownloadStatus.DOWNLOADING,
            DownloadStatus.PAUSED,
            -> active.add(row)
            DownloadStatus.COMPLETED -> completed.add(row)
            DownloadStatus.FAILED, DownloadStatus.CANCELLED -> dismissed.add(row)
        }
    }
    return HistoryUiState(
        active = active,
        completed = completed,
        dismissed = dismissed,
        isLoading = isLoading,
        hasError = isError,
    )
}

private fun HistoryItem.toUiHistoryRow(
    availability: MediaFileAvailability,
): UiHistoryRow = UiHistoryRow(
    id = id,
    title = title.ifBlank { fileName ?: "download" },
    host = sourceUrl.hostOrNull(),
    status = status,
    mimeType = mimeType,
    // Real persisted quality label — producers leave it null today, and a
    // null is never replaced by a guess (rendered only when actually set).
    quality = quality,
    sizeBytes = sizeBytes,
    // PHASE 22: real container duration persisted at enqueue.
    durationSeconds = durationSeconds,
    completedAtEpochMs = completedAt?.toEpochMilli(),
    // The real persisted failure reason only for FAILED rows.
    errorMessage = if (status == DownloadStatus.FAILED) errorMessage else null,
    // Availability only ever applies to completed rows.
    availability = if (status == DownloadStatus.COMPLETED) availability else MediaFileAvailability.NOT_APPLICABLE,
)

/**
 * Host of this URL, or null when unparsable. Never returns the full URL;
 * deliberately tolerant (any URI form) and never throws.
 */
private fun String.hostOrNull(): String? = try {
    URI(this).host?.lowercase()?.takeIf { it.isNotBlank() }
} catch (_: Exception) {
    null
}
