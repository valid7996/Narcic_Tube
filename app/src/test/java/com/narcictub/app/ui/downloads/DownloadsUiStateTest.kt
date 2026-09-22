package com.narcictub.app.ui.downloads

import com.narcictub.app.domain.model.DownloadProgress
import com.narcictub.app.domain.model.DownloadStatus
import com.narcictub.app.domain.model.DownloadsOverview
import com.narcictub.app.domain.model.HistoryItem
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 7 UI-state mapping tests: the pure DownloadsOverview →
 * DownloadsUiState function that drives the Downloads screen sections.
 * Pins section assignment for every status, host-only URL exposure,
 * progress routing, and title fallbacks.
 */
class DownloadsUiStateTest {

    private fun item(
        id: Long,
        status: DownloadStatus,
        url: String = "https://cdn.example.com/file.mp4",
        title: String = "file.mp4",
        errorMessage: String? = null,
        sizeBytes: Long = 0,
        completed: Boolean = false,
    ) = HistoryItem(
        id = id,
        sourceUrl = url,
        title = title,
        status = status,
        sizeBytes = sizeBytes,
        localUri = if (completed) "content://media/external/downloads/$id" else null,
        createdAt = Instant.ofEpochMilli(id),
        completedAt = if (completed) Instant.ofEpochMilli(id) else null,
        errorMessage = errorMessage,
    )

    @Test
    fun `every status lands in exactly one section`() {
        val overview = DownloadsOverview(
            items = listOf(
                item(1, DownloadStatus.DOWNLOADING),
                item(2, DownloadStatus.QUEUED),
                item(3, DownloadStatus.PAUSED),
                item(4, DownloadStatus.COMPLETED, completed = true),
                item(5, DownloadStatus.FAILED, errorMessage = "HTTP error 404"),
                item(6, DownloadStatus.CANCELLED),
            ),
        )

        val state = overview.toUiState()

        // PAUSED groups with active (see the dedicated paused test).
        assertEquals(listOf(1L, 3L), state.active.map { it.id })
        assertEquals(listOf(2L), state.queue.map { it.id })
        assertEquals(listOf(4L, 5L, 6L), state.finished.map { it.id })
        assertFalse(state.isEmpty)
    }

    @Test
    fun `empty overview maps to empty state`() {
        val state = DownloadsOverview().toUiState()
        assertTrue(state.isEmpty)
        assertFalse(state.hasFinished)
    }

    // ===== Phase 9: loading vs empty distinction =====

    @Test
    fun `pre-emission overview shows loading not empty`() {
        val state = DownloadsOverview().toUiState()
        assertTrue(state.isLoading)
        assertTrue(state.isEmpty)
        assertTrue("no snapshot yet must render as loading", state.showLoading)
    }

    @Test
    fun `first real snapshot clears the loading flag`() {
        val state = DownloadsOverview(
            items = listOf(item(1, DownloadStatus.QUEUED)),
            isLoading = false,
        ).toUiState()
        assertFalse(state.isLoading)
        assertFalse(state.showLoading)
        assertFalse(state.isEmpty)
    }

    @Test
    fun `loaded snapshot with zero rows is empty not loading`() {
        val state = DownloadsOverview(isLoading = false).toUiState()
        assertFalse(state.isLoading)
        assertTrue(state.isEmpty)
        assertFalse("genuinely empty must not render a spinner", state.showLoading)
    }

    // ===== Phase 9: retry affordance matches repository capability =====

    @Test
    fun `retry is offered for failed and cancelled rows only`() {
        assertTrue(offersRetryAction(DownloadStatus.FAILED))
        assertTrue(offersRetryAction(DownloadStatus.CANCELLED))
        assertFalse(offersRetryAction(DownloadStatus.COMPLETED))
        assertFalse(offersRetryAction(DownloadStatus.QUEUED))
        assertFalse(offersRetryAction(DownloadStatus.DOWNLOADING))
        assertFalse(offersRetryAction(DownloadStatus.PAUSED))
    }

    @Test
    fun `paused rows group with active not queue`() {
        val state = DownloadsOverview(items = listOf(item(1, DownloadStatus.PAUSED))).toUiState()
        assertEquals(listOf(1L), state.active.map { it.id })
        assertTrue(state.queue.isEmpty())
    }

    @Test
    fun `host is exposed but never the full url`() {
        val state = DownloadsOverview(
            items = listOf(item(1, DownloadStatus.QUEUED, url = "https://user@evil.example.com/a/b?token=xyz")),
        ).toUiState()

        assertEquals("evil.example.com", state.queue.single().host)
        // The row model itself carries no full URL anywhere to render.
        val row = state.queue.single()
        assertFalse(row.title.contains("token"))
        assertNotNull(row.host)
    }

    @Test
    fun `unparsable url degrades host to null instead of echoing input`() {
        val state = DownloadsOverview(
            items = listOf(item(1, DownloadStatus.QUEUED, url = "not a url at all")),
        ).toUiState()
        assertNull(state.queue.single().host)
    }

    @Test
    fun `live progress routes to active rows only`() {
        val overview = DownloadsOverview(
            items = listOf(
                item(1, DownloadStatus.DOWNLOADING),
                item(2, DownloadStatus.COMPLETED, completed = true, sizeBytes = 100),
                item(3, DownloadStatus.FAILED, errorMessage = "x"),
            ),
            progress = mapOf(
                1L to DownloadProgress(10, totalBytes = 100),
                2L to DownloadProgress(50, totalBytes = 100), // stale/ignored
                3L to DownloadProgress(5, totalBytes = 100), // ignored: failed
            ),
        )

        val state = overview.toUiState()

        assertEquals(0.1f, state.active.single().progress!!.fraction!!, 0.0001f)
        assertNull("finished rows never render live progress", state.finished[0].progress)
        assertNull("failed rows never render live progress", state.finished[1].progress)
    }

    @Test
    fun `determinate vs indeterminate progress distinction`() {
        val overview = DownloadsOverview(
            items = listOf(
                item(1, DownloadStatus.DOWNLOADING),
                item(2, DownloadStatus.DOWNLOADING),
            ),
            progress = mapOf(
                1L to DownloadProgress(700_000, totalBytes = 2_100_000),
                2L to DownloadProgress(100, totalBytes = null),
            ),
        )

        val state = overview.toUiState()
        assertEquals(700_000f / 2_100_000f, state.active[0].progress!!.fraction!!, 0.0001f)
        assertNull("unknown Content-Length must yield a null fraction", state.active[1].progress!!.fraction)
    }

    @Test
    fun `blank title falls back to filename then download`() {
        val state = DownloadsOverview(
            items = listOf(
                item(1, DownloadStatus.QUEUED, title = "  "),
                item(2, DownloadStatus.QUEUED, title = "ok.mp4"),
            ),
        ).toUiState()
        assertEquals("download", state.queue[0].title)
        assertEquals("ok.mp4", state.queue[1].title)
    }

    @Test
    fun `error message only surfaces on failed rows`() {
        val state = DownloadsOverview(
            items = listOf(
                item(1, DownloadStatus.FAILED, errorMessage = "HTTP error 404"),
                item(2, DownloadStatus.COMPLETED, completed = true),
            ),
        ).toUiState()
        assertEquals("HTTP error 404", state.finished[0].errorMessage)
        assertNull(state.finished[1].errorMessage)
    }

    @Test
    fun `completed bytes persist to the finished row`() {
        val state = DownloadsOverview(
            items = listOf(item(1, DownloadStatus.COMPLETED, completed = true, sizeBytes = 2_500_000)),
        ).toUiState()
        assertEquals(2_500_000L, state.finished.single().completedBytes)
        assertNotNull(state.finished.single().completedAtEpochMs)
    }

    @Test
    fun `mixed statuses map to their sections in newest-first order`() {
        // HistoryRepository emits newest-first; sections must preserve that.
        val state = DownloadsOverview(
            items = listOf(
                item(3, DownloadStatus.DOWNLOADING),
                item(2, DownloadStatus.DOWNLOADING),
                item(1, DownloadStatus.COMPLETED, completed = true),
            ),
        ).toUiState()
        assertEquals(listOf(3L, 2L), state.active.map { it.id })
        assertEquals(listOf(1L), state.finished.map { it.id })
    }

    // ===== Phase 7 fix round: predicates driving the UI =====

    @Test
    fun `hasFailed is true only while failed or cancelled rows exist`() {
        assertFalse(DownloadsOverview().toUiState().hasFailed)
        assertFalse(
            DownloadsOverview(items = listOf(item(1, DownloadStatus.COMPLETED, completed = true))).toUiState().hasFailed,
        )
        assertTrue(
            DownloadsOverview(items = listOf(item(1, DownloadStatus.FAILED, errorMessage = "x"))).toUiState().hasFailed,
        )
        assertTrue(
            DownloadsOverview(items = listOf(item(1, DownloadStatus.CANCELLED))).toUiState().hasFailed,
        )
        // Mixed finished list: still true while any failed row remains.
        assertTrue(
            DownloadsOverview(
                items = listOf(
                    item(1, DownloadStatus.COMPLETED, completed = true),
                    item(2, DownloadStatus.CANCELLED),
                ),
            ).toUiState().hasFailed,
        )
    }

    @Test
    fun `only completed removal requires confirmation because only it deletes a file`() {
        assertTrue(requiresRemovalConfirmation(DownloadStatus.COMPLETED))
        assertFalse(requiresRemovalConfirmation(DownloadStatus.FAILED))
        assertFalse(requiresRemovalConfirmation(DownloadStatus.CANCELLED))
        assertFalse(requiresRemovalConfirmation(DownloadStatus.QUEUED))
        assertFalse(requiresRemovalConfirmation(DownloadStatus.DOWNLOADING))
        assertFalse(requiresRemovalConfirmation(DownloadStatus.PAUSED))
    }

    @Test
    fun `cancel action is offered for queued, downloading and paused rows`() {
        assertTrue(offersCancelAction(DownloadStatus.QUEUED))
        assertTrue(offersCancelAction(DownloadStatus.DOWNLOADING))
        // PAUSED has no live job, but the repository still supports fully
        // abandoning a paused row without first resuming it.
        assertTrue(offersCancelAction(DownloadStatus.PAUSED))
        assertFalse(offersCancelAction(DownloadStatus.COMPLETED))
        assertFalse(offersCancelAction(DownloadStatus.FAILED))
        assertFalse(offersCancelAction(DownloadStatus.CANCELLED))
    }

    @Test
    fun `pause action is offered only for a live transfer`() {
        assertTrue(offersPauseAction(DownloadStatus.QUEUED))
        assertTrue(offersPauseAction(DownloadStatus.DOWNLOADING))
        assertFalse(offersPauseAction(DownloadStatus.PAUSED))
        assertFalse(offersPauseAction(DownloadStatus.COMPLETED))
        assertFalse(offersPauseAction(DownloadStatus.FAILED))
        assertFalse(offersPauseAction(DownloadStatus.CANCELLED))
    }

    @Test
    fun `resume action is offered only for a paused row`() {
        assertTrue(offersResumeAction(DownloadStatus.PAUSED))
        assertFalse(offersResumeAction(DownloadStatus.QUEUED))
        assertFalse(offersResumeAction(DownloadStatus.DOWNLOADING))
        assertFalse(offersResumeAction(DownloadStatus.COMPLETED))
        assertFalse(offersResumeAction(DownloadStatus.FAILED))
        assertFalse(offersResumeAction(DownloadStatus.CANCELLED))
    }
}
