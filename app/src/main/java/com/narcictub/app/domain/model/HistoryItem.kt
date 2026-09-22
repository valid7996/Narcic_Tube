package com.narcictub.app.domain.model

import java.time.Instant

/** Lifecycle of a history entry. */
enum class DownloadStatus { QUEUED, DOWNLOADING, PAUSED, COMPLETED, FAILED, CANCELLED }

/** Kind of media an entry represents. */
enum class MediaFormat { AUDIO, VIDEO, IMAGE, OTHER }

/**
 * Domain record of one resolved/downloaded item. Pure domain model — the
 * Room entity lives in the data layer and maps to this via HistoryMappers.
 */
data class HistoryItem(
    val id: Long = 0L,
    val sourceUrl: String,
    val title: String,
    val fileName: String? = null,
    val mimeType: String? = null,
    val quality: String? = null,
    val format: MediaFormat = MediaFormat.OTHER,
    val sizeBytes: Long = 0L,
    val localUri: String? = null,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val createdAt: Instant,
    val completedAt: Instant? = null,
    val errorMessage: String? = null,
    /** PHASE 22: real container duration (seconds) carried from the
     *  resolved variant at enqueue time; null when unknown — never a guess. */
    val durationSeconds: Long? = null,
)
