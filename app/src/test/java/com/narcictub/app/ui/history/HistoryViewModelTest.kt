package com.narcictub.app.ui.history

import com.narcictub.app.domain.model.DownloadProgress
import com.narcictub.app.domain.model.DownloadStatus
import com.narcictub.app.domain.model.HistoryItem
import com.narcictub.app.domain.model.MediaFileAvailability
import com.narcictub.app.domain.model.OpenableMedia
import com.narcictub.app.domain.repository.DownloadRepository
import com.narcictub.app.domain.usecase.CheckMediaAvailabilityUseCase
import com.narcictub.app.domain.usecase.OpenCompletedMediaUseCase
import com.narcictub.app.domain.usecase.ObserveDownloadsUseCase
import com.narcictub.app.domain.usecase.RemoveCompletedDownloadsUseCase
import com.narcictub.app.domain.usecase.RemoveDownloadUseCase
import com.narcictub.app.domain.usecase.RemoveFailedDownloadsUseCase
import com.narcictub.app.domain.usecase.RemoveHistoryRecordUseCase
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
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
 * PHASE 10 — HistoryViewModel tests: lazy availability checks, the open
 * request flow, record-only vs file+record removal semantics, duplicate-tap
 * suppression and safe message surfaces. All through domain use cases —
 * no UI layer ever touches storage.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private class FakeDownloadRepository : DownloadRepository {
        val items = MutableStateFlow<List<HistoryItem>>(emptyList())
        override val progress = MutableStateFlow<Map<Long, DownloadProgress>>(emptyMap())

        /** PHASE 12: simulate a persistence failure for the observe stream. */
        var failObserve = false
        override fun observeDownloads() =
            if (failObserve) flow<List<HistoryItem>> { throw RuntimeException("db boom") } else items

        var availabilityResult = MediaFileAvailability.AVAILABLE
        val availabilityChecks = mutableListOf<Long>()

        var openable: OpenableMedia? = null
        private val openGate = CompletableDeferred<Unit>()
        var gateOpen = false
        val openCalls = mutableListOf<Long>()

        fun releaseOpen() {
            openGate.complete(Unit)
        }

        val recordRemovals = mutableListOf<Long>()
        val fileRemovals = mutableListOf<Long>()
        var failedClearCount = 0
        var completedClearCount = 0
        var failActions = false

        override suspend fun enqueue(sourceUrl: String, durationSeconds: Long?): Long = items.value.size + 1L
        override suspend fun cancel(id: Long) {}
        override suspend fun retry(id: Long): Long? = null
        override suspend fun remove(id: Long): Boolean {
            if (failActions) throw RuntimeException("boom")
            fileRemovals.add(id)
            return true
        }
        override suspend fun removeCompleted(): Int {
            if (failActions) throw RuntimeException("boom")
            return ++completedClearCount * 2
        }
        override suspend fun removeFailed(): Int {
            if (failActions) throw RuntimeException("boom")
            return ++failedClearCount * 3
        }
        override suspend fun recoverInterrupted(): Int = 0

        override suspend fun mediaAvailability(id: Long): MediaFileAvailability {
            if (failActions) throw RuntimeException("boom")
            availabilityChecks.add(id)
            return availabilityResult
        }

        override suspend fun openableMedia(id: Long): OpenableMedia? {
            if (failActions) throw RuntimeException("boom")
            openCalls.add(id)
            if (gateOpen) openGate.await()
            return openable
        }

        override suspend fun removeRecord(id: Long): Boolean {
            if (failActions) throw RuntimeException("boom")
            recordRemovals.add(id)
            return true
        }
    }

    private lateinit var repo: FakeDownloadRepository

    private fun viewModel() = HistoryViewModel(
        observeDownloads = ObserveDownloadsUseCase(repo),
        checkAvailability = CheckMediaAvailabilityUseCase(repo),
        openMedia = OpenCompletedMediaUseCase(repo),
        removeRecordAction = RemoveHistoryRecordUseCase(repo),
        removeFileAndRecord = RemoveDownloadUseCase(repo),
        clearFailed = RemoveFailedDownloadsUseCase(repo),
        clearCompleted = RemoveCompletedDownloadsUseCase(repo),
    )

    private fun completedRow(
        id: Long,
        uri: String = "content://media/external/downloads/$id",
        mimeType: String? = "video/mp4",
    ) = HistoryItem(
        id = id,
        sourceUrl = "https://cdn.example.com/file-$id.mp4?token=secret",
        title = "file-$id.mp4",
        mimeType = mimeType,
        status = DownloadStatus.COMPLETED,
        sizeBytes = 2048,
        localUri = uri,
        createdAt = Instant.ofEpochMilli(id),
        completedAt = Instant.ofEpochMilli(id),
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

    @Test
    fun `completed rows are availability-checked once per row`() = runTest {
        repo.items.value = listOf(
            completedRow(1),
            completedRow(2),
            HistoryItem(
                id = 3,
                sourceUrl = "https://cdn.example.com/q.mp4",
                title = "q.mp4",
                status = DownloadStatus.QUEUED,
                createdAt = Instant.ofEpochMilli(3),
            ),
        )
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(
            mapOf(1L to MediaFileAvailability.AVAILABLE, 2L to MediaFileAvailability.AVAILABLE),
            vm.availability.value,
        )
        assertEquals(listOf(1L, 2L), repo.availabilityChecks)

        // A re-emission of the same snapshot must not re-check known rows.
        repo.items.value = repo.items.value
        advanceUntilIdle()
        assertEquals(2, repo.availabilityChecks.size)
    }

    @Test
    fun `open emits a validated pending request for an available file`() = runTest {
        repo.items.value = listOf(completedRow(7))
        val vm = viewModel()
        advanceUntilIdle()

        repo.openable = OpenableMedia("content://media/external/downloads/7", "video/mp4")
        vm.onOpen(7)
        advanceUntilIdle()

        assertEquals(listOf(7L), repo.openCalls)
        val pending = vm.transient.value.pendingOpen
        assertNotNull(pending)
        assertEquals("content://media/external/downloads/7", pending?.uriText)
        assertEquals("video/mp4", pending?.mimeType)
        assertEquals(MediaFileAvailability.AVAILABLE, vm.availability.value[7L])
        assertNull(vm.transient.value.errorMessage)
    }

    @Test
    fun `open of a missing file reports unavailable honestly`() = runTest {
        repo.items.value = listOf(completedRow(7))
        val vm = viewModel()
        advanceUntilIdle()

        repo.openable = null // file no longer resolves
        vm.onOpen(7)
        advanceUntilIdle()

        assertNull(vm.transient.value.pendingOpen)
        assertEquals(MediaFileAvailability.UNAVAILABLE, vm.availability.value[7L])
        assertEquals(
            "The downloaded file is no longer available on this device.",
            vm.transient.value.infoMessage,
        )
    }

    @Test
    fun `duplicate open while in flight is suppressed`() = runTest {
        repo.items.value = listOf(completedRow(7))
        val vm = viewModel()
        advanceUntilIdle()

        repo.openable = OpenableMedia("content://media/external/downloads/7", "video/mp4")
        repo.gateOpen = true
        vm.onOpen(7)
        testScheduler.runCurrent() // reach the gate inside openableMedia
        vm.onOpen(7) // suppressed
        repo.releaseOpen()
        advanceUntilIdle()

        assertEquals(1, repo.openCalls.size)
        assertNotNull(vm.transient.value.pendingOpen)
    }

    @Test
    fun `open request is consumed after the screen shows it`() = runTest {
        repo.items.value = listOf(completedRow(7))
        val vm = viewModel()
        advanceUntilIdle()
        repo.openable = OpenableMedia("content://media/external/downloads/7", "video/mp4")
        vm.onOpen(7)
        advanceUntilIdle()
        assertNotNull(vm.transient.value.pendingOpen)

        vm.onOpenRequestShown()
        assertNull(vm.transient.value.pendingOpen)
    }

    @Test
    fun `launch failure surfaces a safe message`() = runTest {
        val vm = viewModel()
        vm.onOpenLaunchFailed()
        assertEquals("No installed app can open this file.", vm.transient.value.errorMessage)
        assertFalse(vm.transient.value.errorMessage!!.contains("Exception"))
    }

    @Test
    fun `remove record never deletes the file and is duplicate-guarded`() = runTest {
        val vm = viewModel()
        vm.onRemoveRecord(5)
        vm.onRemoveRecord(5) // in flight → suppressed
        advanceUntilIdle()

        assertEquals(listOf(5L), repo.recordRemovals)
        assertEquals("record-only removal must not route to file deletion", 0, repo.fileRemovals.size)
    }

    @Test
    fun `delete file and record routes to the destructive removal`() = runTest {
        val vm = viewModel()
        vm.onDeleteFileAndRecord(9)
        advanceUntilIdle()

        assertEquals(listOf(9L), repo.fileRemovals)
        assertEquals("destructive removal is a separate path", 0, repo.recordRemovals.size)
    }

    @Test
    fun `failed action surfaces a safe message without internals`() = runTest {
        repo.failActions = true
        val vm = viewModel()
        vm.onRemoveRecord(1)
        advanceUntilIdle()

        assertEquals("Couldn't remove that record.", vm.transient.value.errorMessage)
        assertFalse(vm.transient.value.errorMessage!!.contains("boom"))
    }

    @Test
    fun `clear failed reports removed record count`() = runTest {
        val vm = viewModel()
        vm.onClearFailedConfirmed()
        advanceUntilIdle()

        assertEquals("Removed 3 records.", vm.transient.value.infoMessage)
        assertNull(vm.transient.value.errorMessage)
    }

    @Test
    fun `message shown resets messages but keeps a pending open`() = runTest {
        val vm = viewModel()
        vm.onOpenLaunchFailed()
        assertNotNull(vm.transient.value.errorMessage)

        repo.items.value = listOf(completedRow(1))
        advanceUntilIdle()
        repo.openable = OpenableMedia("content://media/external/downloads/1", "video/mp4")
        vm.onOpen(1)
        advanceUntilIdle()
        assertNotNull(vm.transient.value.pendingOpen)

        vm.onMessageShown()
        assertNull(vm.transient.value.errorMessage)
        assertNotNull("pending open must survive a message reset", vm.transient.value.pendingOpen)
    }

    @Test
    fun `play emits a validated navigation request for available media`() = runTest {
        repo.items.value = listOf(completedRow(7))
        val vm = viewModel()
        advanceUntilIdle()

        repo.openable = OpenableMedia("content://media/external/downloads/7", "video/mp4")
        vm.onPlay(7)
        advanceUntilIdle()

        assertEquals(listOf(7L), repo.openCalls)
        assertEquals(7L, vm.transient.value.pendingPlayback)
        assertNull(vm.transient.value.errorMessage)
    }

    @Test
    fun `duplicate play while a launch is pending is suppressed`() = runTest {
        repo.items.value = listOf(completedRow(7))
        val vm = viewModel()
        advanceUntilIdle()
        repo.openable = OpenableMedia("content://media/external/downloads/7", "video/mp4")

        vm.onPlay(7)
        vm.onPlay(7) // pending launch → suppressed
        advanceUntilIdle()

        assertEquals(1, repo.openCalls.size)
        assertEquals(7L, vm.transient.value.pendingPlayback)

        vm.onPlaybackLaunched()
        assertNull(vm.transient.value.pendingPlayback)

        // After consumption a new play is allowed.
        vm.onPlay(7)
        advanceUntilIdle()
        assertEquals(2, repo.openCalls.size)
    }

    @Test
    fun `play of missing media reports unavailable and never navigates`() = runTest {
        repo.items.value = listOf(completedRow(7))
        val vm = viewModel()
        advanceUntilIdle()

        repo.openable = null
        vm.onPlay(7)
        advanceUntilIdle()

        assertNull(vm.transient.value.pendingPlayback)
        assertEquals(MediaFileAvailability.UNAVAILABLE, vm.availability.value[7L])
        assertEquals(
            "The downloaded file is no longer available on this device.",
            vm.transient.value.infoMessage,
        )
    }

    @Test
    fun `availability check failure does not fabricate a verdict`() = runTest {
        repo.failActions = true
        repo.items.value = listOf(completedRow(1))
        val vm = viewModel()
        advanceUntilIdle()

        assertTrue(
            "unknown availability must stay absent from the map",
            vm.availability.value.isEmpty(),
        )
    }

    // ===== Phase 12: load errors, retry and bulk clear-completed =====

    @Test
    fun `persistence failure maps to a safe error state without crashing`() = runTest {
        repo.failObserve = true
        val vm = viewModel()
        advanceUntilIdle()

        assertTrue(vm.overview.value.isError)
        assertTrue("a failed load must not present fabricated items", vm.overview.value.items.isEmpty())
        // Raw exception details must never reach any UI-facing state.
        assertFalse(vm.overview.value.toString().contains("db boom"))
    }

    @Test
    fun `retry after a load failure recovers the real list`() = runTest {
        repo.failObserve = true
        val vm = viewModel()
        advanceUntilIdle()
        assertTrue(vm.overview.value.isError)

        // The repository heals (e.g. transient DB issue resolved).
        repo.failObserve = false
        repo.items.value = listOf(completedRow(4))
        vm.onRetryLoad()
        advanceUntilIdle()

        assertFalse(vm.overview.value.isError)
        assertEquals(1, vm.overview.value.items.size)
    }

    @Test
    fun `clear completed dispatches the existing use case and reports files deletion`() = runTest {
        val vm = viewModel()
        vm.onClearCompletedConfirmed()
        advanceUntilIdle()

        assertEquals(1, repo.completedClearCount)
        assertEquals("Removed 2 downloads and their files.", vm.transient.value.infoMessage)
        assertNull(vm.transient.value.errorMessage)
    }

    @Test
    fun `clear completed failure surfaces a safe message`() = runTest {
        repo.failActions = true
        val vm = viewModel()
        vm.onClearCompletedConfirmed()
        advanceUntilIdle()

        assertEquals("Couldn't delete that download.", vm.transient.value.errorMessage)
        assertFalse(vm.transient.value.errorMessage!!.contains("boom"))
    }
}
