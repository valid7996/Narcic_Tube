package com.narcictub.app.domain.model

/**
 * PHASE 17: media platforms NarcicTub can RECOGNIZE by URL. Recognition is
 * host-based and deliberately separate from extraction support — a provider
 * being detected here does NOT mean its media can be extracted or
 * downloaded (extraction must be explicitly implemented by an extractor).
 */
enum class MediaProvider(val displayName: String) {
    YOUTUBE("YouTube"),
    INSTAGRAM("Instagram"),
    UNKNOWN("Unknown"),
}
