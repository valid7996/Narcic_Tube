package com.narcictub.app.ui.history

import com.narcictub.app.domain.model.DownloadStatus
import com.narcictub.app.domain.model.DownloadsOverview
import com.narcictub.app.domain.model.HistoryItem
import com.narcictub.app.domain.model.MediaFileAvailability
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 10 — History UI-state mapping: lifecycle grouping, honest
 * availability on completed rows, and URL privacy (host only, never the
 * full source URL or the localUri).
 */
class HistoryUiStateTest {

    private fun item(
        id: Long,
        status: DownloadStatus,
        url: String = "https://cdn.example.com/file-$id.mp4?token=secret",
        title: String = "file-$id.mp4",
        mimeType: String? = null,
        sizeBytes: Long = 0,
        completed: Boolean = false,
        errorMessage: String? = null,
    ) = HistoryItem(
        id = id,
        sourceUrl = url,
        title = title,
        mimeType = mimeType,
        status = status,
        sizeBytes = sizeBytes,
        localUri = if (completed) "content://media/external/downloads/$id" else null,
        createdAt = Instant.ofEpochMilli(id),
        completedAt = if (completed) Instant.ofEpochMilli(id) else null,
        errorMessage = errorMessage,
    )

    private val available = mapOf<Long, MediaFileAvailability>()

    @Test
    fun `statuses land in exactly one section`() {
        val state = DownloadsOverview(
            isLoading = false,
            items = listOf(
                item(1, DownloadStatus.QUEUED),
                item(2, DownloadStatus.DOWNLOADING),
                item(3, DownloadStatus.PAUSED),
                item(4, DownloadStatus.COMPLETED, completed = true),
                item(5, DownloadStatus.FAILED, errorMessage = "HTTP error 404"),
                item(6, DownloadStatus.CANCELLED),
            ),
        ).toHistoryUiState(available)

        assertEquals(listOf(1L, 2L, 3L), state.active.map { it.id })
        assertEquals(listOf(4L), state.completed.map { it.id })
        assertEquals(listOf(5L, 6L), state.dismissed.map { it.id })
        assertFalse(state.isEmpty)
        assertTrue(state.hasClearableFailed)
    }

    @Test
    fun `loading and empty are distinct states`() {
        val loading = DownloadsOverview().toHistoryUiState(available)
        assertTrue(loading.showLoading)

        val loadedEmpty = DownloadsOverview(isLoading = false).toHistoryUiState(available)
        assertFalse(loadedEmpty.showLoading)
        assertTrue(loadedEmpty.isEmpty)
    }

    // ===== Phase 12: load-error state, clear-completed, quality =====

    @Test
    fun `load failure maps to the error state`() {
        val state = DownloadsOverview(isLoading = false, isError = true).toHistoryUiState(available)
        assertTrue(state.hasError)
        assertTrue(state.showError)
        assertTrue(state.isEmpty)
        assertFalse(state.showLoading)
    }

    @Test
    fun `load failure with already-visible records keeps rows and flags the error`() {
        // A stream that succeeded once and failed later must not hide real
        // records — the screen shows a banner instead of an empty page.
        val state = DownloadsOverview(
            isLoading = false,
            isError = true,
            items = listOf(item(1, DownloadStatus.COMPLETED, completed = true)),
        ).toHistoryUiState(available)

        assertTrue(state.hasError)
        assertFalse("existing records must stay visible", state.showError)
        assertEquals(1, state.completed.size)
    }

    @Test
    fun `successful snapshot never carries the error state`() {
        val state = DownloadsOverview(
            isLoading = false,
            items = listOf(item(1, DownloadStatus.COMPLETED, completed = true)),
        ).toHistoryUiState(available)
        assertFalse(state.hasError)
        assertFalse(state.showError)
    }

    @Test
    fun `real quality label passes through when present`() {
        val state = DownloadsOverview(
            isLoading = false,
            items = listOf(item(1, DownloadStatus.COMPLETED, completed = true)),
        ).toHistoryUiState(available)
        assertNull(state.completed.single().quality)

        val withQuality = DownloadsOverview(
            isLoading = false,
            items = listOf(
                HistoryItem(
                    id = 2,
                    sourceUrl = "https://cdn.example.com/hd.mp4",
                    title = "hd.mp4",
                    quality = "1080p",
                    status = DownloadStatus.COMPLETED,
                    localUri = "content://media/external/downloads/2",
                    createdAt = Instant.ofEpochMilli(2),
                    completedAt = Instant.ofEpochMilli(2),
                ),
            ),
        ).toHistoryUiState(available)
        assertEquals("1080p", withQuality.completed.single().quality)
    }

    @Test
    fun `clear-completed affordance exists only while completed records exist`() {
        assertFalse(
            DownloadsOverview(isLoading = false).toHistoryUiState(available).hasClearableCompleted,
        )
        assertTrue(
            DownloadsOverview(
                isLoading = false,
                items = listOf(item(1, DownloadStatus.COMPLETED, completed = true)),
            ).toHistoryUiState(available).hasClearableCompleted,
        )
        assertFalse(
            DownloadsOverview(
                isLoading = false,
                items = listOf(item(1, DownloadStatus.FAILED, errorMessage = "x")),
            ).toHistoryUiState(available).hasClearableCompleted,
        )
    }

    @Test
    fun `availability routes to completed rows only`() {
        val state = DownloadsOverview(
            isLoading = false,
            items = listOf(
                item(1, DownloadStatus.COMPLETED, completed = true),
                item(2, DownloadStatus.COMPLETED, completed = true),
                item(3, DownloadStatus.FAILED, errorMessage = "x"),
            ),
        ).toHistoryUiState(
            mapOf(1L to MediaFileAvailability.AVAILABLE, 2L to MediaFileAvailability.UNAVAILABLE),
        )

        assertEquals(MediaFileAvailability.AVAILABLE, state.completed[0].availability)
        assertEquals(MediaFileAvailability.UNAVAILABLE, state.completed[1].availability)
        assertEquals(
            "failed rows never carry a file-availability verdict",
            MediaFileAvailability.NOT_APPLICABLE,
            state.dismissed[0].availability,
        )
    }

    @Test
    fun `un-checked completed rows stay not-applicable not unavailable`() {
        val state = DownloadsOverview(
            isLoading = false,
            items = listOf(item(1, DownloadStatus.COMPLETED, completed = true)),
        ).toHistoryUiState(available)
        assertEquals(MediaFileAvailability.NOT_APPLICABLE, state.completed.single().availability)
    }

    @Test
    fun `rows never contain the full url or query data`() {
        val state = DownloadsOverview(
            isLoading = false,
            items = listOf(
                item(1, DownloadStatus.QUEUED, url = "https://user@evil.example.com/a/b?token=xyz"),
                item(2, DownloadStatus.COMPLETED, completed = true),
            ),
        ).toHistoryUiState(available)

        assertEquals("evil.example.com", state.active.single().host)
        val everyRenderedField = listOf(
            state.active.single().title,
            state.active.single().host,
            state.completed.single().title,
            state.completed.single().host,
        )
        for (field in everyRenderedField) {
            assertFalse("'$field' leaked URL data", field.orEmpty().contains("token"))
            assertFalse(field.orEmpty().contains("https://"))
        }
    }

    @Test
    fun `unparsable url degrades host to null`() {
        val state = DownloadsOverview(
            isLoading = false,
            items = listOf(item(1, DownloadStatus.QUEUED, url = "not a url")),
        ).toHistoryUiState(available)
        assertNull(state.active.single().host)
    }

    @Test
    fun `error message surfaces only on failed rows`() {
        val state = DownloadsOverview(
            isLoading = false,
            items = listOf(
                item(1, DownloadStatus.FAILED, errorMessage = "HTTP error 404"),
                item(2, DownloadStatus.CANCELLED),
                item(3, DownloadStatus.COMPLETED, completed = true),
            ),
        ).toHistoryUiState(available)

        assertEquals("HTTP error 404", state.dismissed[0].errorMessage)
        assertNull(state.dismissed[1].errorMessage)
        assertNull(state.completed.single().errorMessage)
    }

    @Test
    fun `mime type and size pass through when known`() {
        val state = DownloadsOverview(
            isLoading = false,
            items = listOf(
                item(1, DownloadStatus.COMPLETED, completed = true, mimeType = "video/mp4", sizeBytes = 2048),
            ),
        ).toHistoryUiState(available)

        val row = state.completed.single()
        assertEquals("video/mp4", row.mimeType)
        assertEquals(2048L, row.sizeBytes)
    }
}
