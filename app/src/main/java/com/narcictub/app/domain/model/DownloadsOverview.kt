package com.narcictub.app.domain.model

/**
 * Combined snapshot for the Downloads UI: persisted history items plus the
 * live progress of currently active transfers (keyed by history id).
 */
data class DownloadsOverview(
    val items: List<HistoryItem> = emptyList(),
    val progress: Map<Long, DownloadProgress> = emptyMap(),
)
