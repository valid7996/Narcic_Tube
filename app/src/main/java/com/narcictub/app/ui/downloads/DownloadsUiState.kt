package com.narcictub.app.ui.downloads

import com.narcictub.app.domain.model.DownloadProgress
import com.narcictub.app.domain.model.DownloadStatus
import com.narcictub.app.domain.model.DownloadsOverview
import com.narcictub.app.domain.model.HistoryItem
import java.net.URI

/**
 * Pure UI-state mapping for the Downloads screen (Phase 7). One [row model
 * per history item, derived from [DownloadsOverview] — no extra state held
 * in the ViewModel beyond a transient action message.
 *
 * SECURITY: only the source host is exposed ([UiDownload.host]), never the
 * full URL — URLs may embed paths, tokens or query parameters the user
 * would not want on screen. Host extraction failures degrade to null
 * rather than echoing raw input.
 */
data class DownloadsUiState(
    val active: List<UiDownload> = emptyList(),
    val queue: List<UiDownload> = emptyList(),
    val finished: List<UiDownload> = emptyList(),
    val isLoading: Boolean = false,
) {
    val isEmpty: Boolean get() = active.isEmpty() && queue.isEmpty() && finished.isEmpty()
    val hasFinished: Boolean get() = finished.isNotEmpty()

    /**
     * The screen must show a loading placeholder only while no snapshot has
     * arrived at all; once real data lands, empty and loading are distinct.
     */
    val showLoading: Boolean get() = isLoading && isEmpty

    /** True while failed/cancelled records exist — drives "Clear failed". */
    val hasFailed: Boolean get() =
        finished.any { it.status == DownloadStatus.FAILED || it.status == DownloadStatus.CANCELLED }
}

/** One rendered download row. */
data class UiDownload(
    val id: Long,
    val title: String,
    val host: String?,
    val status: DownloadStatus,
    val progress: DownloadProgress?,
    val errorMessage: String?,
    val completedBytes: Long,
    val completedAtEpochMs: Long?,
)

/** Maps a domain snapshot into the three UI sections. */
fun DownloadsOverview.toUiState(): DownloadsUiState {
    val active = mutableListOf<UiDownload>()
    val queue = mutableListOf<UiDownload>()
    val finished = mutableListOf<UiDownload>()

    for (item in items) {
        val row = item.toUiDownload(progress[item.id])
        when (item.status) {
            DownloadStatus.QUEUED -> queue.add(row)
            DownloadStatus.DOWNLOADING, DownloadStatus.PAUSED -> active.add(row)
            DownloadStatus.COMPLETED, DownloadStatus.FAILED, DownloadStatus.CANCELLED -> finished.add(row)
        }
    }
    return DownloadsUiState(active = active, queue = queue, finished = finished, isLoading = isLoading)
}

private fun HistoryItem.toUiDownload(progress: DownloadProgress?): UiDownload = UiDownload(
    id = id,
    title = title.ifBlank { fileName ?: "download" },
    host = sourceUrl.hostOrNull(),
    status = status,
    // Only active rows have live progress; finished rows use their persisted
    // size (and never fake a fraction).
    progress = if (status == DownloadStatus.DOWNLOADING || status == DownloadStatus.PAUSED) progress else null,
    errorMessage = if (status == DownloadStatus.FAILED) errorMessage else null,
    completedBytes = sizeBytes,
    completedAtEpochMs = completedAt?.toEpochMilli(),
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

/**
 * The only status whose removal ALSO deletes a published file — the
 * destructive case that must be confirmed in the UI before acting
 * (Phase 7 fix round, Qwen finding 2). Failed/cancelled rows hold no
 * file, so their removal stays immediate.
 */
fun requiresRemovalConfirmation(status: DownloadStatus): Boolean =
    status == DownloadStatus.COMPLETED

/**
 * Rows that offer the cancel action. PAUSED is included: the repository
 * fully supports cancelling a paused row (it just has no live coroutine
 * job to cancel — see DownloadRepositoryImpl.cancel's PAUSED branch), and a
 * paused download the user no longer wants should still be abandonable
 * without first resuming it.
 */
fun offersCancelAction(status: DownloadStatus): Boolean =
    status == DownloadStatus.QUEUED ||
        status == DownloadStatus.DOWNLOADING ||
        status == DownloadStatus.PAUSED

/** Rows that offer the pause action: only a live transfer can be paused. */
fun offersPauseAction(status: DownloadStatus): Boolean =
    status == DownloadStatus.QUEUED || status == DownloadStatus.DOWNLOADING

/** Rows that offer the resume action: only a paused row can be resumed. */
fun offersResumeAction(status: DownloadStatus): Boolean =
    status == DownloadStatus.PAUSED

/**
 * Rows that offer the retry action (Phase 9): the repository re-queues both
 * FAILED and CANCELLED rows from their stored source URL, so the UI offers
 * the affordance for exactly those states. COMPLETED rows are never
 * retryable (the file already exists); in-flight rows must be cancelled
 * first.
 */
fun offersRetryAction(status: DownloadStatus): Boolean =
    status == DownloadStatus.FAILED || status == DownloadStatus.CANCELLED
