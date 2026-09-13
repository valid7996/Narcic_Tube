package com.narcictub.app.domain.repository

import com.narcictub.app.domain.model.DownloadProgress
import com.narcictub.app.domain.model.HistoryItem
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
     * @throws java.io.IOException on persistence failure
     */
    suspend fun enqueue(sourceUrl: String): Long

    /** User-initiated cancel of a queued or active download. */
    suspend fun cancel(id: Long)
}
