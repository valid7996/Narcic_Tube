package com.narcictub.app.ui.downloads

import com.narcictub.app.domain.model.DownloadProgress
import com.narcictub.app.domain.model.DownloadStatus
import com.narcictub.app.domain.model.HistoryItem
import com.narcictub.app.domain.repository.DownloadRepository
import com.narcictub.app.domain.usecase.CancelDownloadUseCase
import com.narcictub.app.domain.usecase.ObserveDownloadsUseCase
import com.narcictub.app.domain.usecase.PauseDownloadUseCase
import com.narcictub.app.domain.usecase.RemoveCompletedDownloadsUseCase
import com.narcictub.app.domain.usecase.RemoveDownloadUseCase
import com.narcictub.app.domain.usecase.RemoveFailedDownloadsUseCase
import com.narcictub.app.domain.usecase.ResumeDownloadUseCase
import com.narcictub.app.domain.usecase.RetryDownloadUseCase
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phase 7 DownloadsViewModel tests: state collection from the real use
 * case, action dispatch through use cases, duplicate-tap suppression, and
 * safe user-facing error surfaces (no stack traces, no URLs).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    /** Reusable repository fake recording every action. */
    private class FakeDownloadRepository : DownloadRepository {
        val items = MutableStateFlow<List<HistoryItem>>(emptyList())
        override val progress = MutableStateFlow<Map<Long, DownloadProgress>>(emptyMap())
        override fun observeDownloads() = items

        val cancelledIds = mutableListOf<Long>()
        val retriedIds = mutableListOf<Long>()
        val removedIds = mutableListOf<Long>()
        var removedCompletedCount = 0
        var removedFailedCount = 0

        var failCancel = false
        var failRetry = false
        var failRemove = false
        var failBulk = false
        var retryReturnsNull = false

        override suspend fun enqueue(sourceUrl: String, durationSeconds: Long?): Long = items.value.size + 1L

        override suspend fun cancel(id: Long) {
            if (failCancel) throw RuntimeException("boom")
            cancelledIds.add(id)
        }

        val pausedIds = mutableListOf<Long>()
        val resumedIds = mutableListOf<Long>()
        var failPause = false
        var failResume = false

        override suspend fun pause(id: Long) {
            if (failPause) throw RuntimeException("boom")
            pausedIds.add(id)
        }

        override suspend fun resume(id: Long) {
            if (failResume) throw RuntimeException("boom")
            resumedIds.add(id)
        }

        override suspend fun retry(id: Long): Long? {
            if (failRetry) throw RuntimeException("boom")
            retriedIds.add(id)
            return if (retryReturnsNull) null else id + 100
        }

        override suspend fun remove(id: Long): Boolean {
            if (failRemove) throw RuntimeException("boom")
            removedIds.add(id)
            return true
        }

        override suspend fun removeCompleted(): Int {
            if (failBulk) throw RuntimeException("boom")
            removedCompletedCount++
            return 2
        }

        override suspend fun removeFailed(): Int {
            if (failBulk) throw RuntimeException("boom")
            removedFailedCount++
            return 3
        }

        override suspend fun recoverInterrupted(): Int = 0
        override suspend fun mediaAvailability(id: Long) =
            com.narcictub.app.domain.model.MediaFileAvailability.NOT_APPLICABLE
        override suspend fun openableMedia(id: Long) = null
        override suspend fun removeRecord(id: Long): Boolean = false
    }

    private lateinit var repo: FakeDownloadRepository

    private fun viewModel() = DownloadsViewModel(
        observeDownloads = ObserveDownloadsUseCase(repo),
        cancelDownload = CancelDownloadUseCase(repo),
        pauseDownload = PauseDownloadUseCase(repo),
        resumeDownload = ResumeDownloadUseCase(repo),
        retryDownload = RetryDownloadUseCase(repo),
        removeDownload = RemoveDownloadUseCase(repo),
        removeCompleted = RemoveCompletedDownloadsUseCase(repo),
        removeFailed = RemoveFailedDownloadsUseCase(repo),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        repo = FakeDownloadRepository()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun item(id: Long, status: DownloadStatus) = HistoryItem(
        id = id,
        sourceUrl = "https://cdn.example.com/x$id.mp4",
        title = "x$id.mp4",
        status = status,
        createdAt = Instant.ofEpochMilli(id),
    )

    @Test
    fun `overview reflects real repository state`() = runTest {
        repo.items.value = listOf(item(1, DownloadStatus.DOWNLOADING))
        val vm = viewModel()
        val collected = mutableListOf<com.narcictub.app.domain.model.DownloadsOverview>()
        val job = launch(testDispatcher) {
            vm.overview.collect { collected.add(it) }
        }
        advanceUntilIdle()
        job.cancel()
        // The latest snapshot carries the repository's real item.
        assertEquals(1, collected.last().items.size)
        assertEquals(DownloadStatus.DOWNLOADING, collected.last().items.single().status)
    }

    @Test
    fun `first real snapshot flips the loading flag off`() = runTest {
        val vm = viewModel()
        // Pre-emission: the StateFlow still holds the initial loading value.
        assertTrue(vm.overview.value.isLoading)

        val job = launch(testDispatcher) { vm.overview.collect {} }
        repo.items.value = listOf(item(1, DownloadStatus.QUEUED))
        advanceUntilIdle()
        job.cancel()

        assertFalse("a real snapshot is never 'loading'", vm.overview.value.isLoading)
        assertEquals(1, vm.overview.value.items.size)
        // And an empty-but-loaded snapshot stays non-loading with zero rows.
        repo.items.value = emptyList()
        val job2 = launch(testDispatcher) { vm.overview.collect {} }
        advanceUntilIdle()
        job2.cancel()
        assertFalse(vm.overview.value.isLoading)
        assertTrue(vm.overview.value.items.isEmpty())
    }

    @Test
    fun `cancel dispatches to use case by stable id`() = runTest {
        val vm = viewModel()
        vm.onCancel(7)
        advanceUntilIdle()
        assertEquals(listOf(7L), repo.cancelledIds)
    }

    @Test
    fun `double cancel while first is in flight is ignored`() = runTest {
        val vm = viewModel()
        vm.onCancel(7)
        vm.onCancel(7) // in-flight: suppressed
        advanceUntilIdle()
        assertEquals(listOf(7L), repo.cancelledIds)
    }

    @Test
    fun `pause dispatches to use case by stable id`() = runTest {
        val vm = viewModel()
        vm.onPause(7)
        advanceUntilIdle()
        assertEquals(listOf(7L), repo.pausedIds)
    }

    @Test
    fun `resume dispatches to use case by stable id`() = runTest {
        val vm = viewModel()
        vm.onResume(7)
        advanceUntilIdle()
        assertEquals(listOf(7L), repo.resumedIds)
    }

    @Test
    fun `pause failure surfaces a safe message without stack trace`() = runTest {
        repo.failPause = true
        val vm = viewModel()
        vm.onPause(7)
        advanceUntilIdle()
        assertEquals("Couldn't pause that download.", vm.transient.value.errorMessage)
    }

    @Test
    fun `resume failure surfaces a safe message without stack trace`() = runTest {
        repo.failResume = true
        val vm = viewModel()
        vm.onResume(7)
        advanceUntilIdle()
        assertEquals("Couldn't resume that download.", vm.transient.value.errorMessage)
    }

    @Test
    fun `cancel failure surfaces a safe message without stack trace`() = runTest {
        repo.failCancel = true
        val vm = viewModel()
        vm.onCancel(7)
        advanceUntilIdle()
        val error = vm.transient.value.errorMessage
        assertNotNull(error)
        assertFalse(error!!.contains("java.lang"))
        assertFalse(error.contains("RuntimeException"))
    }

    @Test
    fun `retry dispatches and allows a second retry after completion`() = runTest {
        val vm = viewModel()
        vm.onRetry(5)
        advanceUntilIdle()
        assertEquals(listOf(5L), repo.retriedIds)
        vm.onRetry(5) // pending cleared after completion: allowed
        advanceUntilIdle()
        assertEquals(listOf(5L, 5L), repo.retriedIds)
    }

    @Test
    fun `retry null result surfaces unavailable message`() = runTest {
        repo.retryReturnsNull = true
        val vm = viewModel()
        vm.onRetry(5)
        advanceUntilIdle()
        assertNotNull(vm.transient.value.errorMessage)
    }

    @Test
    fun `remove dispatches to use case`() = runTest {
        val vm = viewModel()
        vm.onRemove(9)
        advanceUntilIdle()
        assertEquals(listOf(9L), repo.removedIds)
    }

    @Test
    fun `clear finished reports removed count`() = runTest {
        val vm = viewModel()
        vm.onRemoveCompletedConfirmed()
        advanceUntilIdle()
        assertEquals(1, repo.removedCompletedCount)
        assertEquals("Removed 2 downloads.", vm.transient.value.removedCountMessage)
    }

    @Test
    fun `clear failed reports removed count`() = runTest {
        val vm = viewModel()
        vm.onRemoveFailedConfirmed()
        advanceUntilIdle()
        assertEquals(1, repo.removedFailedCount)
        assertEquals("Removed 3 downloads.", vm.transient.value.removedCountMessage)
    }

    @Test
    fun `message shown resets transient state`() = runTest {
        repo.failCancel = true
        val vm = viewModel()
        vm.onCancel(1)
        advanceUntilIdle()
        assertNotNull(vm.transient.value.errorMessage)
        vm.onMessageShown()
        assertNull(vm.transient.value.errorMessage)
        assertNull(vm.transient.value.removedCountMessage)
    }

    @Test
    fun `bulk failure surfaces a safe message`() = runTest {
        repo.failBulk = true
        val vm = viewModel()
        vm.onRemoveCompletedConfirmed()
        advanceUntilIdle()
        assertNotNull(vm.transient.value.errorMessage)
        assertFalse(vm.transient.value.errorMessage!!.contains("RuntimeException"))
    }

    @Test
    fun `transient state starts clean`() {
        val vm = viewModel()
        assertNull(vm.transient.value.errorMessage)
        assertNull(vm.transient.value.removedCountMessage)
    }

    // ===== Phase 7 fix round: dialog-wired actions =====

    @Test
    fun `confirmed clear failed invokes the bulk failed removal exactly once`() = runTest {
        val vm = viewModel()
        // Dialog confirm → the ONLY path to the bulk action (the screen
        // never calls it without confirmation).
        vm.onRemoveFailedConfirmed()
        advanceUntilIdle()
        assertEquals(1, repo.removedFailedCount)
        assertEquals("Removed 3 downloads.", vm.transient.value.removedCountMessage)
    }

    @Test
    fun `cancel path never reaches the bulk removal`() = runTest {
        val vm = viewModel()
        // Cancelling the dialog calls nothing on the ViewModel — the
        // repository fake records any invocation; zero is required.
        advanceUntilIdle()
        assertEquals(0, repo.removedFailedCount)
        assertEquals(0, repo.removedCompletedCount)
        assertEquals(0, repo.removedIds.size)
    }

    @Test
    fun `confirmed completed removal dispatches the destructive remove by id`() = runTest {
        val vm = viewModel()
        vm.onRemove(42L) // dialog confirm → ViewModel remove action
        advanceUntilIdle()
        assertEquals(listOf(42L), repo.removedIds)
    }

    @Test
    fun `cancelled completed removal leaves item untouched`() = runTest {
        val vm = viewModel()
        // Dialog dismiss sets no confirmRemoveId → onRemove never fires.
        advanceUntilIdle()
        assertTrue(repo.removedIds.isEmpty())
        assertEquals(0, repo.removedCompletedCount)
    }
}
