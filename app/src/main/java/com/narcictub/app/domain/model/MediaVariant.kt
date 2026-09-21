package com.narcictub.app.domain.model

/**
 * PHASE 18: one concrete, downloadable rendition of a resolved media item.
 * Every field is REAL — derived from actual HTTP responses, container
 * bytes, or policy-validated redirects. Null means unknown; nothing is
 * ever synthesized. For direct-file sources NarcicTub produces exactly one
 * variant (the file itself); provider extractors (future phases) may
 * produce several.
 */
data class MediaVariant(
    /** Policy-validated URL the bytes come from. */
    val downloadUrl: String,
    val mimeType: String? = null,
    /** Real container identity (e.g. "mp4"/"m4a" from the ftyp brand). */
    val container: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val sizeBytes: Long? = null,
    val durationSeconds: Long? = null,
    /** Derived from the REAL height only (e.g. "1080p"); null when unknown. */
    val qualityLabel: String? = null,
)
