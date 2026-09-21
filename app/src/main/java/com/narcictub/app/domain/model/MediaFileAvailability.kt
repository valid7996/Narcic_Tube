package com.narcictub.app.domain.model

/**
 * Honest availability of a COMPLETED record's published file, checked
 * against the actual storage backend. NOT_APPLICABLE means the question
 * does not apply (row missing, not completed, or no persisted URI) — it is
 * never conflated with "missing file", and nothing here is ever guessed.
 */
enum class MediaFileAvailability { AVAILABLE, UNAVAILABLE, NOT_APPLICABLE }

/**
 * The minimal data the UI needs to act on a completed record's published
 * file — launching the standard ACTION_VIEW intent (Phase 10) or in-app
 * playback (Phase 11). Produced ONLY after the URI passed the safety policy
 * and the file was confirmed to resolve — the UI never receives an
 * unvalidated URI.
 */
data class OpenableMedia(
    val uriText: String,
    val mimeType: String?,
    /** Real persisted record title (Phase 11) — shown on the player, never a URL. */
    val title: String? = null,
)
