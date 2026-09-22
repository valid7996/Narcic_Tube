package com.narcictub.app.domain.model

/**
 * Metadata resolved from a source URL. Produced by MediaResolver.
 *
 * HONESTY CONTRACT (Phase 8): every field is real — derived only from actual
 * server responses or the URL itself. A null means "unknown", never a
 * placeholder or a guess. No duration, quality, title, size or MIME type is
 * ever synthesized by the resolver.
 */
data class MediaInfo(
    val sourceUrl: String,
    /** Server-provided name (Content-Disposition) or the URL's file name; null when neither exists. */
    val title: String? = null,
    val host: String,
    /** Host-based provider recognition (PHASE 17) — recognition is not extraction support. */
    val provider: MediaProvider = MediaProvider.UNKNOWN,
    /** Unknown for direct files (no metadata parsing is performed) — stays null. */
    val durationSeconds: Long? = null,
    val thumbnailUrl: String? = null,
    /** Server-declared Content-Type or magic-byte sniffed MIME; null when genuinely unknown. */
    val mimeType: String? = null,
    /** Server-declared size (Content-Length / Content-Range total); null when absent or invalid. */
    val sizeBytes: Long? = null,
    /** Quality label ONLY when actually known/derived from real data; null otherwise. */
    val qualityLabel: String? = null,
    /** PHASE 18: real decoded video width/height (container bytes); null when unknown. */
    val videoWidth: Int? = null,
    val videoHeight: Int? = null,
    /** PHASE 18: real average bitrate computed from real size + real duration; null otherwise. */
    val bitrateBps: Long? = null,
    /**
     * PHASE 18: concrete downloadable renditions. Direct-file sources carry
     * exactly one variant; empty until real variant data exists.
     */
    val variants: List<MediaVariant> = emptyList(),
    /**
     * True when the URL was confirmed to serve the media bytes directly
     * (2xx response on the final hop). Download enqueue is offered only for
     * direct files — platform pages are reported unsupported instead.
     */
    val isDirectFile: Boolean = false,
    /** Final URL after policy-validated redirects — where the bytes actually come from. */
    val downloadUrl: String? = null,
)
