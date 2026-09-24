package com.narcictub.app.ui.share

import com.narcictub.app.domain.model.MediaVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure presentation rules for the Share download screen's format list:
 * grouping from real MIME/dimension data, honest labels, size formatting
 * and the documented group order (Music, Video, Other) with the shared
 * per-group deterministic sort. Nothing is ever invented for unknowns.
 */
class ShareFormatUiTest {

    private fun variant(
        mimeType: String? = null,
        container: String? = null,
        width: Int? = null,
        height: Int? = null,
        sizeBytes: Long? = null,
        qualityLabel: String? = null,
        url: String = "https://example.com/f",
    ) = MediaVariant(
        downloadUrl = url,
        mimeType = mimeType,
        container = container,
        width = width,
        height = height,
        sizeBytes = sizeBytes,
        qualityLabel = qualityLabel,
    )

    // ===== grouping =====

    @Test
    fun `audio mime groups as music`() {
        assertEquals(FormatGroup.MUSIC, formatGroupFor(variant(mimeType = "audio/mp4")))
    }

    @Test
    fun `video mime groups as video`() {
        assertEquals(FormatGroup.VIDEO, formatGroupFor(variant(mimeType = "video/mp4")))
    }

    @Test
    fun `real dimensions group as video even without mime`() {
        assertEquals(FormatGroup.VIDEO, formatGroupFor(variant(width = 1920, height = 1080)))
    }

    @Test
    fun `unknown metadata groups as other`() {
        assertEquals(FormatGroup.OTHER, formatGroupFor(variant(container = "bin")))
    }

    // ===== labels =====

    @Test
    fun `quality label wins when known`() {
        assertEquals("1080p", variantLabel(variant(qualityLabel = "1080p", container = "mp4")))
    }

    @Test
    fun `container is uppercased when no quality label exists`() {
        assertEquals("M4A", variantLabel(variant(container = "m4a")))
    }

    @Test
    fun `honest fallback when nothing is known`() {
        assertEquals("Original", variantLabel(variant()))
    }

    // ===== size formatting =====

    @Test
    fun `null and non-positive sizes stay hidden`() {
        assertNull(formatBytes(null))
        assertNull(formatBytes(0L))
    }

    @Test
    fun `bytes kilobytes and megabytes format`() {
        assertEquals("512 B", formatBytes(512L))
        assertEquals("2.0 KB", formatBytes(2048L))
        assertEquals("16.3 MB", formatBytes(16_300_000L))
    }

    @Test
    fun `gigabytes format with two decimals`() {
        assertEquals("1.50 GB", formatBytes(1_500_000_000L))
    }

    // ===== list assembly =====

    @Test
    fun `groups appear in music video other order and empty groups are skipped`() {
        val rows = buildFormatRows(
            listOf(
                variant(mimeType = "video/mp4", qualityLabel = "360p", height = 360),
                variant(mimeType = "audio/mp4", container = "m4a", sizeBytes = 1_000),
                variant(mimeType = "video/mp4", qualityLabel = "1080p", height = 1080),
            ),
        )
        assertEquals(
            listOf(FormatGroup.MUSIC, FormatGroup.VIDEO, FormatGroup.VIDEO),
            rows.map { it.group },
        )
        assertEquals("1080p", rows[1].label)
        assertEquals("360p", rows[2].label)
    }

    @Test
    fun `every variant is represented exactly once`() {
        val variants = listOf(
            variant(mimeType = "audio/mp4", container = "m4a", url = "https://a"),
            variant(mimeType = "video/mp4", qualityLabel = "720p", height = 720, url = "https://v"),
            variant(container = "bin", url = "https://o"),
        )
        val rows = buildFormatRows(variants)
        assertEquals(variants.map { it.downloadUrl }.sorted(), rows.map { it.variant.downloadUrl }.sorted())
    }
}
