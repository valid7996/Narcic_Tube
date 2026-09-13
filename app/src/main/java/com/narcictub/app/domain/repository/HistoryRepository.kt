package com.narcictub.app.domain.repository

import com.narcictub.app.domain.model.DownloadStatus
import com.narcictub.app.domain.model.HistoryItem
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/** Persistence contract for the download history. */
interface HistoryRepository {

    /** Inserts a new entry (id assigned by the store) and returns its id. */
    suspend fun add(item: HistoryItem): Long

    /** Emits the full history, newest first. */
    fun observeHistory(): Flow<List<HistoryItem>>

    suspend fun get(id: Long): HistoryItem?

    suspend fun updateStatus(
        id: Long,
        status: DownloadStatus,
        errorMessage: String? = null,
        completedAt: Instant? = null,
    )

    suspend fun delete(id: Long)

    suspend fun clear()
}
