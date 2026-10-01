package com.narcictub.app.domain.model

/**
 * One YouTube search result (real data from the ytsearch extraction —
 * nothing synthesized; null means the field was not provided).
 */
data class YoutubeSearchItem(
    val id: String,
    val title: String,
    val channel: String? = null,
    val durationSeconds: Long? = null,
    val viewCount: Long? = null,
    val thumbnailUrl: String? = null,
    /** The canonical watch URL — handed straight to the resolve flow. */
    val watchUrl: String,
)
