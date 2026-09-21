package com.narcictub.app.data.history

import com.narcictub.app.domain.model.DownloadStatus
import com.narcictub.app.domain.model.HistoryItem
import com.narcictub.app.domain.model.MediaFormat
import java.time.Instant

/**
 * Domain <-> Room mapping. Enums persist as names; unknown names fall back
 * defensively instead of throwing (forward compatibility with future rows).
 */
object HistoryMappers {

    fun HistoryEntity.toDomain(): HistoryItem = HistoryItem(
        id = id,
        sourceUrl = sourceUrl,
        title = title,
        fileName = fileName,
        mimeType = mimeType,
        quality = quality,
        format = runCatching { MediaFormat.valueOf(format) }.getOrDefault(MediaFormat.OTHER),
        sizeBytes = sizeBytes,
        localUri = localUri,
        status = runCatching { DownloadStatus.valueOf(status) }.getOrDefault(DownloadStatus.QUEUED),
        createdAt = Instant.ofEpochMilli(createdAtEpochMs),
        completedAt = completedAtEpochMs?.let(Instant::ofEpochMilli),
        errorMessage = errorMessage,
        durationSeconds = durationSeconds,
    )

    fun HistoryItem.toEntity(): HistoryEntity = HistoryEntity(
        id = id,
        sourceUrl = sourceUrl,
        title = title,
        fileName = fileName,
        mimeType = mimeType,
        quality = quality,
        format = format.name,
        sizeBytes = sizeBytes,
        localUri = localUri,
        status = status.name,
        createdAtEpochMs = createdAt.toEpochMilli(),
        completedAtEpochMs = completedAt?.toEpochMilli(),
        errorMessage = errorMessage,
        durationSeconds = durationSeconds,
    )

    fun List<HistoryEntity>.toDomainList(): List<HistoryItem> = map { it.toDomain() }
}
