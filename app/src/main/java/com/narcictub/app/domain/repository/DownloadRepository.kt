package com.narcictub.app.domain.repository

import com.narcictub.app.domain.model.DownloadProgress
import com.narcictub.app.domain.model.HistoryItem
import com.narcictub.app.domain.model.MediaFileAvailability
import com.narcictub.app.domain.model.OpenableMedia
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Queue + lifecycle manager for downloads. UI talks to this only through
 * use cases; the implementation orchestrates FileDownloader, MediaStore and
 * HistoryRepository.
 */
interface DownloadRepository {

    /** Live progress of currently active downloads, keyed by history id. */
    val progress: StateFlow<Map<Long, DownloadProgress>>

    /** All download records (newest first); backed by the history table. */
    fun observeDownloads(): Flow<List<HistoryItem>>

    /**
     * Registers a new download (QUEUED) and returns its history id.
     * [durationSeconds] is the real container duration resolved for this
     * media (Phase 22) — persisted for the library; null when unknown.
     * @throws java.io.IOException on persistence failure
     */
    suspend fun enqueue(sourceUrl: String, durationSeconds: Long? = null): Long

    /**
     * User-initiated cancel of a queued or active download. Terminal rows
     * (COMPLETED) are never downgraded; see the implementation's L-1 guard.
     */
    suspend fun cancel(id: Long)

    /**
     * Re-queues a FAILED (or CANCELLED) download from its stored source
     * URL. The failed row is deleted and a fresh QUEUED row is inserted, so
     * the retry has a clean lifecycle with its own history id. Returns the
     * new history id, or null when [id] no longer exists (already removed)
     * or is not in a retryable state.
     */
    suspend fun retry(id: Long): Long?

    /**
     * Removes a finished/failed/cancelled record (and, for COMPLETED rows,
     * its published file). In-flight rows are rejected — cancel them first;
     * returns false when [id] is not removable.
     */
    suspend fun remove(id: Long): Boolean

    /** Deletes every finished record (COMPLETED) — files + rows. */
    suspend fun removeCompleted(): Int

    /** Deletes every failed/cancelled record — rows only, no files exist. */
    suspend fun removeFailed(): Int

    /**
     * App-startup recovery: any row stuck in a non-terminal state from a
     * previous process (QUEUED/DOWNLOADING/PAUSED) is moved to FAILED with
     * a safe, user-readable reason. Returns the number of rows recovered.
     * No download restarts implicitly — the user retries explicitly.
     * Only rows whose createdAt predates the current process are affected
     * (the implementation captures a process-start boundary), so a
     * download enqueued during this process is never mislabeled.
     */
    suspend fun recoverInterrupted(): Int

    // ===== PHASE 10: history / local-media management =====

    /**
     * Honest availability of a COMPLETED record's published file, checked
     * through the storage backend behind the URI-safety policy.
     * NOT_APPLICABLE when the row is missing, not completed, or has no
     * persisted URI — never a fabricated "missing file" verdict.
     */
    suspend fun mediaAvailability(id: Long): MediaFileAvailability

    /**
     * The validated URI + MIME for opening a COMPLETED record's file, or
     * null when the record is not completed, the file does not resolve, or
     * the URI fails the safety policy. Only policy-passing URIs ever leave
     * the repository.
     */
    suspend fun openableMedia(id: Long): OpenableMedia?

    /**
     * Removes ONLY a history record — terminal rows (COMPLETED, FAILED,
     * CANCELLED) — without touching any published file. "Remove from
     * history" semantics: the user's media stays. In-flight rows are
     * rejected; returns false when [id] is missing or not removable.
     */
    suspend fun removeRecord(id: Long): Boolean
}
