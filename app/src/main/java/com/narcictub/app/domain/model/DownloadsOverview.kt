package com.narcictub.app.domain.model

/**
 * Combined snapshot for the Downloads UI: persisted history items plus the
 * live progress of currently active transfers (keyed by history id).
 *
 * [isLoading] distinguishes "no data yet" from "genuinely no downloads":
 * it is true only for the initial pre-emission value and flips false the
 * moment the repository produces its first real snapshot, so the screen
 * never mistakes a still-loading Room query for an empty queue.
 *
 * [isError] (Phase 12) marks a failed load — the persistence layer could
 * not deliver a snapshot. It never carries raw exception details; screens
 * map it to a safe, user-readable state.
 */
data class DownloadsOverview(
    val items: List<HistoryItem> = emptyList(),
    val progress: Map<Long, DownloadProgress> = emptyMap(),
    val isLoading: Boolean = true,
    val isError: Boolean = false,
)
