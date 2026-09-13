package com.narcictub.app.domain.model

/**
 * Metadata resolved from a source URL. Produced by MediaResolver — until a
 * real implementation exists this type is used only in the resolver contract
 * and its tests; nothing at runtime fakes it.
 */
data class MediaInfo(
    val sourceUrl: String,
    val title: String,
    val host: String,
    val durationSeconds: Long? = null,
    val thumbnailUrl: String? = null,
)
