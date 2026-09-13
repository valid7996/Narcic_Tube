package com.narcictub.app.data.history

import com.narcictub.app.domain.model.DownloadStatus
import com.narcictub.app.domain.model.HistoryItem
import com.narcictub.app.domain.model.MediaFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

import com.narcictub.app.data.history.HistoryMappers.toDomain
import com.narcictub.app.data.history.HistoryMappers.toDomainList
import com.narcictub.app.data.history.HistoryMappers.toEntity

class HistoryMappersTest {

    private val created = Instant.ofEpochMilli(1_700_000_000_000)

    @Test
    fun `domain to entity and back is lossless`() {
        val item = HistoryItem(
            id = 7L,
            sourceUrl = "https://example.com/watch?v=1",
            title = "Sample",
            fileName = "sample.mp4",
            mimeType = "video/mp4",
            quality = "720p",
            format = MediaFormat.VIDEO,
            sizeBytes = 123_456L,
            localUri = "content://media/external/video/1",
            status = DownloadStatus.COMPLETED,
            createdAt = created,
            completedAt = created.plusSeconds(60),
            errorMessage = null,
        )
        val roundTrip = item.toEntity().toDomain()
        assertEquals(item, roundTrip)
    }

    @Test
    fun `nullable fields stay null`() {
        val item = HistoryItem(
            sourceUrl = "https://example.com/a",
            title = "T",
            createdAt = created,
        )
        val roundTrip = item.toEntity().toDomain()
        assertNull(roundTrip.fileName)
        assertNull(roundTrip.completedAt)
        assertNull(roundTrip.errorMessage)
        assertEquals(DownloadStatus.QUEUED, roundTrip.status)
        assertEquals(MediaFormat.OTHER, roundTrip.format)
    }

    @Test
    fun `unknown persisted enum names fall back instead of throwing`() {
        val entity = HistoryEntity(
            id = 1L,
            sourceUrl = "https://example.com/a",
            title = "T",
            format = "HOLOGRAM",
            status = "TELEPORTED",
            createdAtEpochMs = created.toEpochMilli(),
        )
        val domain = entity.toDomain()
        assertEquals(MediaFormat.OTHER, domain.format)
        assertEquals(DownloadStatus.QUEUED, domain.status)
    }

    @Test
    fun `entity list maps preserving order`() {
        val entities = listOf(
            HistoryEntity(id = 1, sourceUrl = "u1", title = "b", format = "AUDIO", status = "QUEUED", createdAtEpochMs = 2),
            HistoryEntity(id = 2, sourceUrl = "u2", title = "a", format = "AUDIO", status = "QUEUED", createdAtEpochMs = 1),
        )
        val domain = entities.toDomainList()
        assertEquals(2, domain.size)
        assertEquals("b", domain[0].title)
        assertEquals("a", domain[1].title)
        assertNotNull(domain)
    }

    @Test
    fun `epoch millis survive the round trip`() {
        val item = HistoryItem(
            sourceUrl = "https://example.com/a",
            title = "T",
            createdAt = Instant.ofEpochMilli(42L),
            completedAt = Instant.ofEpochMilli(99L),
            status = DownloadStatus.COMPLETED,
        )
        val entity = item.toEntity()
        assertEquals(42L, entity.createdAtEpochMs)
        assertEquals(99L, entity.completedAtEpochMs)
        assertTrue(entity.completedAtEpochMs!! > entity.createdAtEpochMs)
    }
}
