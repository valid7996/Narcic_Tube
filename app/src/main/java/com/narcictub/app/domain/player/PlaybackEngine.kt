package com.narcictub.app.domain.player

/**
 * PHASE 11 — a validated, LOCAL media source prepared for playback. The
 * repository produces it only after MediaUriSafety policy + availability
 * checks; the player layer re-checks it once more before preparation.
 * There is deliberately no network playback concept anywhere in this type.
 */
data class PlaybackSource(
    val uriText: String,
    val mimeType: String?,
)

/** Safe, user-mappable playback failure categories — no raw OS codes. */
enum class PlaybackErrorReason {
    /** The URI failed the safety policy — never handed to the player. */
    INVALID_SOURCE,

    /** The file was read but cannot be decoded (codec/container). */
    UNSUPPORTED_FORMAT,

    /** The file could not be read (missing/IO). */
    READ_FAILED,

    /** Any other playback failure. */
    GENERIC,
}

/**
 * PHASE 11 — playback engine abstraction. The ViewModel owns ONE engine per
 * screen instance (survives configuration changes, released on teardown);
 * implementations live in the data layer. A surface is passed as an opaque
 * value so the domain interface stays free of Android UI types.
 */
interface PlaybackEngine {

    fun prepare(source: PlaybackSource, listener: Listener)

    /** Video output surface (android.view.Surface); null detaches. */
    fun attachSurface(surface: Any?)

    /**
     * Starts playback. Returns true when playback ACTUALLY started (audio
     * focus granted) and false when it did not — including audio-focus
     * denial — so callers can keep their UI state in sync with reality.
     */
    fun play(): Boolean

    fun pause()

    fun seekTo(positionMs: Long)

    /** Real current position in ms, or null when not queryable right now. */
    val currentPositionMs: Long?

    fun release()

    interface Listener {
        /** The source is ready: [durationMs] is 0 when genuinely unknown. */
        fun onPrepared(durationMs: Long, hasVideo: Boolean)

        /** Real decoded video dimensions; only reported with valid values. */
        fun onVideoSizeChanged(width: Int, height: Int)

        fun onBuffering(active: Boolean)

        fun onCompletion()

        fun onError(reason: PlaybackErrorReason)

        // ===== PHASE 22: audio-focus callbacks =====

        /**
         * Transient audio-focus loss (e.g. navigation prompt): the engine
         * has paused playback and WILL resume automatically on regaining
         * focus. The UI should reflect a paused state.
         */
        fun onPausedByTransientFocusLoss()

        /**
         * Permanent audio-focus loss (e.g. another media app started): the
         * engine has paused and will NOT auto-resume — replay is explicit.
         */
        fun onPausedByPermanentFocusLoss()

        /** Focus regained and playback automatically resumed. */
        fun onResumedByFocusGain()
    }
}
