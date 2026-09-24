package com.narcictub.app.ui.share

import com.narcictub.app.domain.model.MediaVariant
import com.narcictub.app.ui.home.sortedVariantsForDisplay

/**
 * Presentation helpers for the Share download screen's format list. Pure and
 * unit-testable; every label is derived from REAL variant data only —
 * unknown stays hidden (null), nothing is ever invented.
 */
internal enum class FormatGroup(val label: String) {
    MUSIC("Music"),
    VIDEO("Video"),
    OTHER("Other"),
}

/** One rendered row of the format list. */
internal data class FormatRow(
    val group: FormatGroup,
    val variant: MediaVariant,
    /** Primary label, e.g. "1080p" / "M4A" / "Original". */
    val label: String,
    /** Real, server-reported size ("16.3 MB") or null when unknown. */
    val sizeText: String?,
)

/** Grouping from real data: audio mime → Music; video mime or dimensions → Video. */
internal fun formatGroupFor(variant: MediaVariant): FormatGroup = when {
    variant.mimeType?.startsWith("audio/") == true -> FormatGroup.MUSIC
    variant.mimeType?.startsWith("video/") == true ||
        variant.width != null ||
        variant.height != null -> FormatGroup.VIDEO
    else -> FormatGroup.OTHER
}

/**
 * Primary row label from real data only: the derived quality label
 * ("1080p"), else the real container ("M4A"), else the honest fallback.
 */
internal fun variantLabel(variant: MediaVariant): String {
    variant.qualityLabel?.let { return it }
    variant.container?.let { container -> return container.uppercase() }
    return "Original"
}

/**
 * Human-readable size, e.g. "16.3 MB" — null when the size is unknown.
 * Decimal units (1 MB = 10^6 bytes), matching how download managers and
 * yt-dlp display file sizes.
 */
internal fun formatBytes(bytes: Long?): String? {
    if (bytes == null || bytes <= 0L) return null
    val kb = 1_000.0
    val mb = kb * 1_000
    val gb = mb * 1_000
    return when {
        bytes >= gb -> String.format("%.2f GB", bytes / gb)
        bytes >= mb -> String.format("%.1f MB", bytes / mb)
        bytes >= kb -> String.format("%.1f KB", bytes / kb)
        else -> "$bytes B"
    }
}

/**
 * Rows for the whole list, in the screenshot's order (Music, Video, Other),
 * skipping empty groups. Within a group the shared deterministic ordering
 * applies (video height descending, then size; see Home's sorter).
 */
internal fun buildFormatRows(variants: List<MediaVariant>): List<FormatRow> =
    FormatGroup.entries.flatMap { group ->
        val inGroup = variants.filter { formatGroupFor(it) == group }
        if (inGroup.isEmpty()) {
            emptyList()
        } else {
            sortedVariantsForDisplay(inGroup).map { variant ->
                FormatRow(
                    group = group,
                    variant = variant,
                    label = variantLabel(variant),
                    sizeText = formatBytes(variant.sizeBytes),
                )
            }
        }
    }
