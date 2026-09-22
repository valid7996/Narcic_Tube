package com.narcictub.app.ui.playback

/**
 * PHASE 11 — playback UI state. Real player values only: [durationMs] is
 * null when the media does not report one (never a fabricated value),
 * [aspectRatio] only from actual decoded video dimensions. The state holds
 * NO URI, no path, no query data — nothing sensitive to leak.
 */
data class PlaybackUiState(
    val phase: PlaybackPhase = PlaybackPhase.LOADING,
    val title: String = "",
    val isVideo: Boolean = false,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = true,
    /** PHASE 22: true when playback reached the natural end (replay affordance). */
    val isCompleted: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long? = null,
    val aspectRatio: Float? = null,
    val errorMessage: String? = null,
)

enum class PlaybackPhase {
    /** Validating the record and preparing the player. */
    LOADING,

    /** The record is not completed/available — honest unavailable state. */
    UNAVAILABLE,

    /** Player ready (or playing). */
    READY,

    /** Safe playback failure. */
    ERROR,
}
