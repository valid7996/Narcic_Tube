package com.narcictub.app.data.history

import com.narcictub.app.domain.model.DownloadStatus
import com.narcictub.app.domain.model.HistoryItem
import com.narcictub.app.domain.repository.HistoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

import com.narcictub.app.data.history.HistoryMappers.toDomain
import com.narcictub.app.data.history.HistoryMappers.toDomainList
import com.narcictub.app.data.history.HistoryMappers.toEntity

/**
 * Room-backed HistoryRepository. UI/ViewModel never touch Room directly —
 * they depend on the domain interface only.
 */
@Singleton
class HistoryRepositoryImpl @Inject constructor(
    private val dao: HistoryDao,
) : HistoryRepository {

    override suspend fun add(item: HistoryItem): Long =
        dao.insert(item.toEntity())

    override fun observeHistory(): Flow<List<HistoryItem>> =
        dao.observeAll().map { entities -> entities.toDomainList() }

    override suspend fun get(id: Long): HistoryItem? =
        dao.getById(id)?.toDomain()

    override suspend fun updateStatus(
        id: Long,
        status: DownloadStatus,
        errorMessage: String?,
        completedAt: Instant?,
    ) {
        dao.updateStatus(
            id = id,
            status = status.name,
            errorMessage = errorMessage,
            completedAtEpochMs = completedAt?.toEpochMilli(),
        )
    }

    override suspend fun delete(id: Long) {
        dao.deleteById(id)
    }

    override suspend fun clear() {
        dao.clear()
    }

    override suspend fun updateLocalUri(id: Long, localUri: String) {
        dao.updateLocalUri(id, localUri)
    }

    override suspend fun updateSizeBytes(id: Long, sizeBytes: Long) {
        dao.updateSizeBytes(id, sizeBytes)
    }
}
