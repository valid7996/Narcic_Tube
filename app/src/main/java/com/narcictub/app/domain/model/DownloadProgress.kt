package com.narcictub.app.domain.model

/**
 * Real byte-level progress of an active download. [totalBytes] is null when
 * the server did not announce a Content-Length — the UI must render that as
 * indeterminate, never as an invented percentage.
 */
data class DownloadProgress(
    val downloadedBytes: Long,
    val totalBytes: Long? = null,
) {
    /** Fraction in 0..1, or null when total size is unknown. */
    val fraction: Float?
        get() = totalBytes?.takeIf { it > 0 }?.let { (downloadedBytes.toFloat() / it).coerceIn(0f, 1f) }
}
