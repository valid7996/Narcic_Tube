package com.narcictub.app.ui.playback

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.narcictub.app.domain.model.MediaFileAvailability
import com.narcictub.app.domain.player.PlaybackEngine
import com.narcictub.app.domain.player.PlaybackErrorReason
import com.narcictub.app.domain.player.PlaybackSource
import com.narcictub.app.domain.usecase.CheckMediaAvailabilityUseCase
import com.narcictub.app.domain.usecase.OpenCompletedMediaUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * PHASE 11 — playback screen logic.
 *
 * SECURITY: the record id arrives via navigation; the ViewModel resolves the
 * source ITSELF through the Phase 10 use cases — availability check, then
 * [OpenCompletedMediaUseCase], which only ever returns a URI that passed
 * [com.narcictub.app.data.local.MediaUriSafety]. The engine re-checks the
 * same policy immediately before preparation, so an http/https/custom-scheme
 * source can never reach the player — no network playback exists.
 *
 * LIFECYCLE: exactly one engine (one framework player) per ViewModel; the
 * ViewModel survives configuration changes (no player churn) and releases
 * the engine in [onCleared] when the screen is popped. No GlobalScope, no
 * unmanaged scope — the position ticker runs in viewModelScope.
 *
 * HISTORY SAFETY: this ViewModel has no mutation use cases at all — playback
 * failures cannot change a COMPLETED record, and missing media never deletes
 * history.
 */
@HiltViewModel
class PlaybackViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val checkAvailability: CheckMediaAvailabilityUseCase,
    private val openMedia: OpenCompletedMediaUseCase,
    private val engine: PlaybackEngine,
) : ViewModel() {

    private val itemId: Long =
        savedStateHandle.get<Long>(ITEM_ID_KEY) ?: -1L

    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    /** MIME from the validated record — fallback for video-track detection. */
    private var declaredMime: String? = null

    private val engineListener = object : PlaybackEngine.Listener {
        override fun onPrepared(durationMs: Long, hasVideo: Boolean) {
            _state.update {
                it.copy(
                    phase = PlaybackPhase.READY,
                    isBuffering = false,
                    // Real duration from the player; 0/unreported stays null.
                    durationMs = durationMs.takeIf { d -> d > 0 },
                    // Content truth first (decoded track), MIME as fallback.
                    isVideo = hasVideo || declaredMime?.startsWith("video/") == true,
                )
            }
            // Preview starts immediately when playback actually starts
            // (P22-LOW-1: audio-focus denial keeps the UI non-playing).
            val started = engine.play()
            _state.update { it.copy(isPlaying = started) }
        }

        override fun onVideoSizeChanged(width: Int, height: Int) {
            // Phase 11 review cleanup: degenerate dimensions (reported by
            // some containers before real metadata arrives) must never
            // produce an Infinity/NaN aspect ratio.
            if (width <= 0 || height <= 0) return
            _state.update { it.copy(aspectRatio = width.toFloat() / height.toFloat()) }
        }

        override fun onBuffering(active: Boolean) {
            _state.update { it.copy(isBuffering = active) }
        }

        override fun onCompletion() {
            // PHASE 22: real end-of-media state — replay is one tap away
            // (play while completed restarts from the beginning).
            _state.update { it.copy(isPlaying = false, isCompleted = true) }
        }

        override fun onError(reason: PlaybackErrorReason) {
            _state.update {
                it.copy(
                    phase = PlaybackPhase.ERROR,
                    isPlaying = false,
                    isBuffering = false,
                    errorMessage = messageFor(reason),
                )
            }
        }

        override fun onPausedByTransientFocusLoss() {
            // PHASE 22: another app took focus briefly — the engine paused
            // and will auto-resume; the UI mirrors the real player state.
            _state.update { it.copy(isPlaying = false) }
        }

        override fun onPausedByPermanentFocusLoss() {
            _state.update { it.copy(isPlaying = false) }
        }

        override fun onResumedByFocusGain() {
            _state.update { it.copy(isPlaying = true) }
        }
    }

    init {
        viewModelScope.launch {
            // Re-validate at playback time — availability may have changed
            // since the History screen (never trusting earlier UI state).
            val availability = checkAvailability(itemId)
            if (availability != MediaFileAvailability.AVAILABLE) {
                _state.update {
                    it.copy(phase = PlaybackPhase.UNAVAILABLE, errorMessage = FILE_MISSING)
                }
                return@launch
            }
            val media = openMedia(itemId)
            if (media == null) {
                _state.update {
                    it.copy(phase = PlaybackPhase.UNAVAILABLE, errorMessage = FILE_MISSING)
                }
                return@launch
            }
            declaredMime = media.mimeType
            _state.update { it.copy(title = media.title.orEmpty().ifBlank { "Download" }) }
            // The engine runs the same URI policy again before preparation.
            engine.prepare(PlaybackSource(media.uriText, media.mimeType), engineListener)
        }
    }

    fun onTogglePlay() {
        val current = _state.value
        if (current.phase != PlaybackPhase.READY) return
        if (current.isCompleted) {
            // PHASE 22: replay — restart from the beginning on user action.
            // P22-LOW-1: isPlaying mirrors whether playback actually started.
            engine.seekTo(0)
            _state.update { it.copy(positionMs = 0L, isCompleted = false) }
            val started = engine.play()
            _state.update { it.copy(isPlaying = started) }
            return
        }
        if (current.isPlaying) {
            engine.pause()
            _state.update { it.copy(isPlaying = false) }
        } else {
            // P22-LOW-1: audio-focus denial keeps the UI non-playing.
            val started = engine.play()
            _state.update { it.copy(isPlaying = started) }
        }
    }

    /**
     * PHASE 22: relative seek (±10 s). Position is clamped to [0, duration]
     * when the duration is known; with an unknown duration only the lower
     * bound is clamped here (the platform player clamps its own upper bound
     * and the real position is read back afterwards).
     */
    fun onSeekBy(deltaMs: Long) {
        val current = _state.value
        if (current.phase != PlaybackPhase.READY) return
        val duration = current.durationMs
        val raw = current.positionMs + deltaMs
        val target = when {
            raw < 0 -> 0L
            duration != null && raw > duration -> duration
            else -> raw
        }
        onSeekTo(target)
    }

    /**
     * PHASE 22: another audio app grabbed the route (headphones unplugged,
     * Bluetooth disconnect) — pause immediately; replay stays explicit.
     */
    fun onBecameNoisy() {
        val current = _state.value
        if (current.phase == PlaybackPhase.READY && current.isPlaying) {
            engine.pause()
            _state.update { it.copy(isPlaying = false) }
        }
    }

    fun onSeekTo(positionMs: Long) {
        if (_state.value.phase != PlaybackPhase.READY) return
        // PHASE 22: clamped seeking — never negative, never beyond a known
        // duration; malformed durations are handled by the null duration
        // path (upper clamp left to the platform player).
        val duration = _state.value.durationMs
        val clamped = positionMs
            .coerceAtLeast(0L)
            .let { if (duration != null) it.coerceAtMost(duration) else it }
        engine.seekTo(clamped)
        _state.update { it.copy(positionMs = clamped) }
    }

    /** Video surface lifecycle from the UI (opaque to the domain layer). */
    fun onSurfaceAvailable(surface: Any?) {
        engine.attachSurface(surface)
    }

    fun onSurfaceDestroyed() {
        engine.attachSurface(null)
    }

    /**
     * Real position polling: the UI drives the cadence (500ms while
     * playing) and the value is ALWAYS the player's actual position — there
     * is no simulated progress anywhere. No-op while paused or not ready.
     */
    fun onTick() {
        val current = _state.value
        if (!current.isPlaying || current.phase != PlaybackPhase.READY) return
        val position = engine.currentPositionMs ?: return
        _state.update { it.copy(positionMs = position) }
    }

    private fun messageFor(reason: PlaybackErrorReason): String = when (reason) {
        PlaybackErrorReason.UNSUPPORTED_FORMAT ->
            "This file can't be played on this device — the format isn't supported."
        PlaybackErrorReason.READ_FAILED ->
            "The file couldn't be read for playback."
        else -> "Playback failed."
    }

    override fun onCleared() {
        engine.release()
        super.onCleared()
    }

    companion object {
        const val ITEM_ID_KEY = "itemId"
        private const val FILE_MISSING =
            "The downloaded file is no longer available on this device."
    }
}
