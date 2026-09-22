package com.narcictub.app.data.downloader

import android.content.Context
import com.narcictub.app.data.local.MediaStoreFileWriter
import com.narcictub.app.domain.downloader.DownloadException
import com.narcictub.app.domain.downloader.DownloadFileResult
import com.narcictub.app.domain.downloader.FileDownloader
import com.narcictub.app.domain.model.AppSettings
import com.narcictub.app.domain.model.DownloadProgress
import com.narcictub.app.domain.model.DownloadStatus
import com.narcictub.app.domain.model.HistoryItem
import com.narcictub.app.domain.model.MediaFileAvailability
import com.narcictub.app.domain.repository.HistoryRepository
import com.narcictub.app.domain.repository.SettingsRepository
import com.narcictub.app.domain.model.DownloadLocation
import com.narcictub.app.domain.model.ThemeMode
import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

/**
 * Download state-machine tests through DownloadRepositoryImpl with fake
 * collaborators. Verifies the full lifecycle: QUEUED→DOWNLOADING→COMPLETED /
 * FAILED / CANCELLED, staging cleanup, history persistence, and that
 * completion only happens after a successful MediaStore publish.
 *
 * L-1 (Phase 6 review) regression coverage: the publish→COMPLETED commit is
 * protected from cancellation — a cancel landing mid-commit leaves the row
 * COMPLETED (never CANCELLED/half-committed), and a cancel arriving after
 * completion never downgrades the row.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadRepositoryImplTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val testScope = TestScope(dispatcher)

    private class RecordingDownloader : FileDownloader {
        var failWith: Exception? = null
        var hang = false
        var lastDestination: File? = null

        /** Server-declared type; raw form may carry parameters (Phase 10 fix). */
        var responseMime: String? = "video/mp4"

        private val gate = CompletableDeferred<Unit>()

        override suspend fun download(
            url: String,
            destination: File,
            onProgress: (DownloadProgress) -> Unit,
        ): DownloadFileResult {
            lastDestination = destination
            if (hang) gate.await() // never completes until cancelled
            failWith?.let { throw it }
            onProgress(DownloadProgress(5, totalBytes = 10))
            onProgress(DownloadProgress(10, totalBytes = 10))
            return DownloadFileResult(bytesDownloaded = 10, contentType = responseMime)
        }
    }

    private class FakeHistoryRepository : HistoryRepository {
        val rows = MutableStateFlow<List<HistoryItem>>(emptyList())
        private var nextId = 1L

        override suspend fun add(item: HistoryItem): Long {
            val id = nextId++
            rows.value = rows.value + item.copy(id = id)
            return id
        }

        override fun observeHistory(): Flow<List<HistoryItem>> = rows

        override suspend fun get(id: Long): HistoryItem? = rows.value.firstOrNull { it.id == id }

        override suspend fun updateStatus(id: Long, status: DownloadStatus, errorMessage: String?, completedAt: Instant?) {
            rows.value = rows.value.map {
                if (it.id == id) it.copy(status = status, errorMessage = errorMessage, completedAt = completedAt) else it
            }
        }

        override suspend fun delete(id: Long) { rows.value = rows.value.filterNot { it.id == id } }
        override suspend fun clear() { rows.value = emptyList() }

        override suspend fun deleteByStatuses(statuses: Set<DownloadStatus>) {
            rows.value = rows.value.filterNot { it.status.name in statuses.map { s -> s.name } }
        }

        override suspend fun updateLocalUri(id: Long, localUri: String) {
            rows.value = rows.value.map { if (it.id == id) it.copy(localUri = localUri) else it }
        }

        override suspend fun updateSizeBytes(id: Long, sizeBytes: Long) {
            rows.value = rows.value.map { if (it.id == id) it.copy(sizeBytes = sizeBytes) else it }
        }

        val mimeUpdates = mutableMapOf<Long, String?>()
        override suspend fun updateMimeType(id: Long, mimeType: String?) {
            mimeUpdates[id] = mimeType
            rows.value = rows.value.map { if (it.id == id) it.copy(mimeType = mimeType) else it }
        }
    }

    private class FakeSettingsRepository(private val concurrent: Int = 1) : SettingsRepository {
        override val settings = MutableStateFlow(AppSettings(concurrentDownloads = concurrent))
        override suspend fun setTheme(mode: ThemeMode) {}
        override suspend fun setDownloadLocation(location: DownloadLocation) {}
        override suspend fun setWifiOnly(enabled: Boolean) {}
        override suspend fun setConcurrentDownloads(count: Int) {}
        override suspend fun setNotificationsEnabled(enabled: Boolean) {}
    }

    /** Stub writer: bypasses the real MediaStore on the JVM. */
    private open class StubMediaStoreWriter : MediaStoreFileWriter(
        context = mockk<Context>(relaxed = true),
        settingsRepository = FakeSettingsRepository(),
    ) {
        var publishCalls = 0
        var failPublish = false
        val deleteCalls = mutableListOf<Uri>()

        /** L-1: suspends inside publish until [releasePublish] completes. */
        var gatePublish = false
        val publishEntered = CompletableDeferred<Unit>()
        val releasePublish = CompletableDeferred<Unit>()

        override suspend fun doPublish(
            stagingFile: File,
            displayName: String,
            mimeType: String?,
            subDirectory: String?,
        ): Uri {
            publishCalls++
            if (gatePublish) {
                publishEntered.complete(Unit)
                releasePublish.await()
            }
            if (failPublish) throw java.io.IOException("mediastore boom")
            return mockk<Uri>(relaxed = true)
        }

        override suspend fun delete(uri: Uri): Boolean {
            deleteCalls.add(uri)
            return true
        }
    }

    /** PHASE 10: checker stub — the repository test cares that the impl
     *  consults the checker with the row's URI; the URI→backend resolution
     *  itself is covered in MediaFileCheckerTest/MediaUriSafetyTest. */
    private class StubMediaFileChecker : com.narcictub.app.data.local.MediaFileChecker(
        context = mockk<Context>(relaxed = true),
        uriSafety = com.narcictub.app.data.local.MediaUriSafety(emptyList()),
    ) {
        var result = true
        val checked = mutableListOf<String>()
        override suspend fun isAvailable(uriText: String): Boolean {
            checked.add(uriText)
            return result
        }
    }

    /**
     * L-1 test transport: the FIRST download hangs until [releaseFirst]
     * completes (used to pin the wait-for-permit cancellation path).
     */
    private class FirstHopGatedDownloader : FileDownloader {
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        private var calls = 0

        override suspend fun download(
            url: String,
            destination: File,
            onProgress: (DownloadProgress) -> Unit,
        ): DownloadFileResult {
            calls += 1
            if (calls == 1) {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
            onProgress(DownloadProgress(10, totalBytes = 10))
            return DownloadFileResult(bytesDownloaded = 10, contentType = "video/mp4")
        }
    }

    /** Test subclass isolating staging to the rule-managed temp folder and
     *  running the work scope on the TestScheduler so advanceUntilIdle works. */
    private class TestRepository(
        stagingRoot: File,
        downloader: FileDownloader,
        history: FakeHistoryRepository,
        settings: FakeSettingsRepository,
        writer: StubMediaStoreWriter,
        checker: StubMediaFileChecker,
        scope: kotlinx.coroutines.CoroutineScope,
    ) : DownloadRepositoryImpl(
        context = mockk<Context>(relaxed = true),
        downloader = downloader,
        historyRepository = history,
        settingsRepository = settings,
        mediaStoreWriter = writer,
        mediaFileChecker = checker,
        workScope = scope,
    ) {
        var stagingOverride: File? = null
        private val root = stagingRoot
        override fun stagingRoot(): File = stagingOverride ?: root
    }

    private fun repositoryFull(
        downloader: FileDownloader,
        history: FakeHistoryRepository,
        concurrent: Int = 1,
        checker: StubMediaFileChecker = StubMediaFileChecker(),
    ): Triple<TestRepository, StubMediaStoreWriter, StubMediaFileChecker> {
        val writer = StubMediaStoreWriter()
        val repo = TestRepository(
            stagingRoot = tmp.newFolder(),
            downloader = downloader,
            history = history,
            settings = FakeSettingsRepository(concurrent),
            writer = writer,
            checker = checker,
            scope = testScope,
        )
        return Triple(repo, writer, checker)
    }

    private fun repository(
        downloader: FileDownloader,
        history: FakeHistoryRepository,
        concurrent: Int = 1,
        failPublish: Boolean = false,
        gatePublish: Boolean = false,
    ): Pair<TestRepository, StubMediaStoreWriter> {
        val (repo, writer, _) = repositoryFull(downloader, history, concurrent)
        writer.failPublish = failPublish
        writer.gatePublish = gatePublish
        return repo to writer
    }

    private fun TestRepository.overrideStaging(dir: File) {
        stagingOverride = dir
    }

    @Test
    fun `queued item transitions to downloading then completed with uri and size`() = testScope.runTest {
        val downloader = RecordingDownloader()
        val history = FakeHistoryRepository()
        val (repo, writer) = repository(downloader, history)

        val id = repo.enqueue("https://example.com/video.mp4")
        advanceUntilIdle()

        val item = history.get(id)!!
        assertEquals(DownloadStatus.COMPLETED, item.status)
        assertNotNull(item.localUri)
        assertEquals(10L, item.sizeBytes)
        assertNotNull(item.completedAt)
        assertEquals(1, writer.publishCalls)
    }

    @Test
    fun `downloader failure marks failed with message and cleans staging`() = testScope.runTest {
        val downloader = RecordingDownloader().apply { failWith = DownloadException.Http(404) }
        val history = FakeHistoryRepository()
        val staging = tmp.newFolder()
        val (repo, _) = repository(downloader, history).also { (r, _) -> r.overrideStaging(staging) }

        val id = repo.enqueue("https://example.com/missing.mp4")
        advanceUntilIdle()

        val item = history.get(id)!!
        assertEquals(DownloadStatus.FAILED, item.status)
        assertTrue(item.errorMessage!!.contains("404"))
        assertNull(item.localUri)
        assertNull(item.completedAt)
        // staging cleaned
        val leftovers = staging.listFiles()
        assertTrue(leftovers == null || leftovers.isEmpty())
    }

    @Test
    fun `cancel while downloading marks cancelled not failed`() = testScope.runTest {
        val downloader = RecordingDownloader().apply { hang = true }
        val history = FakeHistoryRepository()
        val (repo, _) = repository(downloader, history)

        val id = repo.enqueue("https://example.com/slow.mp4")
        advanceUntilIdle()
        assertEquals(DownloadStatus.DOWNLOADING, history.get(id)!!.status)

        repo.cancel(id)
        advanceUntilIdle()

        val item = history.get(id)!!
        assertEquals(DownloadStatus.CANCELLED, item.status)
        assertNull(item.completedAt)
    }

    @Test
    fun `publish failure means not completed`() = testScope.runTest {
        val downloader = RecordingDownloader()
        val history = FakeHistoryRepository()
        val (repo, _) = repository(downloader, history, failPublish = true)

        val id = repo.enqueue("https://example.com/x.mp4")
        advanceUntilIdle()

        val item = history.get(id)!!
        assertEquals(DownloadStatus.FAILED, item.status)
        assertNull(item.localUri) // never COMPLETED-without-file
        assertNull(item.completedAt)
    }

    @Test
    fun `second item waits when concurrency is one`() = testScope.runTest {
        val downloader = RecordingDownloader()
        val history = FakeHistoryRepository()
        val (repo, _) = repository(downloader, history, concurrent = 1)

        val id1 = repo.enqueue("https://example.com/a.mp4")
        val id2 = repo.enqueue("https://example.com/b.mp4")
        advanceUntilIdle()

        // Both complete eventually (permit released after the first).
        assertEquals(DownloadStatus.COMPLETED, history.get(id1)!!.status)
        assertEquals(DownloadStatus.COMPLETED, history.get(id2)!!.status)
    }

    @Test
    fun `observe downloads reflects history repo`() = testScope.runTest {
        val history = FakeHistoryRepository()
        val (repo, _) = repository(RecordingDownloader(), history, concurrent = 2)
        repo.enqueue("https://example.com/1.mp4")
        repo.enqueue("https://example.com/2.mp4")
        advanceUntilIdle()
        val list = repo.observeDownloads().first()
        assertEquals(2, list.size)
    }

    @Test
    fun `staging file removed after success`() = testScope.runTest {
        val downloader = RecordingDownloader()
        val history = FakeHistoryRepository()
        val staging = tmp.newFolder()
        val (repo, _) = repository(downloader, history).also { (r, _) -> r.overrideStaging(staging) }

        val id = repo.enqueue("https://example.com/a.mp4")
        advanceUntilIdle()

        assertEquals(DownloadStatus.COMPLETED, history.get(id)!!.status)
        val leftovers = staging.listFiles()
        assertTrue(leftovers == null || leftovers.isEmpty())
    }

    @Test
    fun `staging file cleaned after cancellation`() = testScope.runTest {
        val downloader = RecordingDownloader().apply { hang = true }
        val history = FakeHistoryRepository()
        val staging = tmp.newFolder()
        val (repo, _) = repository(downloader, history).also { (r, _) -> r.overrideStaging(staging) }

        val id = repo.enqueue("https://example.com/slow.mp4")
        advanceUntilIdle()
        repo.cancel(id)
        advanceUntilIdle()

        assertEquals(DownloadStatus.CANCELLED, history.get(id)!!.status)
        val leftovers = staging.listFiles()
        assertTrue(leftovers == null || leftovers.isEmpty())
    }

    // ===== L-1: commit-phase cancellation protection =====

    @Test
    fun `cancel landing mid commit still completes the row`() = testScope.runTest {
        // Download finishes streaming; publish() then suspends on its gate.
        val downloader = RecordingDownloader()
        val history = FakeHistoryRepository()
        val staging = tmp.newFolder()
        val (repo, writer) = repository(downloader, history, gatePublish = true)
            .also { (r, _) -> r.overrideStaging(staging) }

        val id = repo.enqueue("https://example.com/x.mp4")
        advanceUntilIdle()

        // Publish has started and is suspended INSIDE the commit phase.
        assertTrue(writer.publishEntered.isCompleted)

        // User cancels MID-COMMIT (after publish started, before it returns).
        repo.cancel(id)
        advanceUntilIdle()
        assertTrue(
            "the row must not have been downgraded while the commit was suspended",
            history.get(id)!!.status != DownloadStatus.CANCELLED,
        )

        // The commit finishes: COMPLETED, not CANCELLED.
        writer.releasePublish.complete(Unit)
        advanceUntilIdle()

        val item = history.get(id)!!
        assertEquals(DownloadStatus.COMPLETED, item.status)
        assertNotNull(item.localUri)
        assertNotNull(item.completedAt)
        // And the staging file is gone.
        val leftovers = staging.listFiles()
        assertTrue(leftovers == null || leftovers.isEmpty())
    }

    @Test
    fun `cancel after completed never downgrades the row`() = testScope.runTest {
        val downloader = RecordingDownloader()
        val history = FakeHistoryRepository()
        val (repo, _) = repository(downloader, history)

        val id = repo.enqueue("https://example.com/x.mp4")
        advanceUntilIdle()
        assertEquals(DownloadStatus.COMPLETED, history.get(id)!!.status)

        // A late cancel (e.g. user taps a stale list entry) must not flip a
        // finished row to CANCELLED.
        repo.cancel(id)
        advanceUntilIdle()

        assertEquals(DownloadStatus.COMPLETED, history.get(id)!!.status)
    }

    @Test
    fun `cancel while waiting for a permit marks cancelled not queued`() = testScope.runTest {
        // Concurrency 1: the first download holds the permit; the second
        // waits. Cancelling the waiter must record CANCELLED (via the
        // pumpQueue guard), never leave it QUEUED.
        val gated = FirstHopGatedDownloader()
        val history = FakeHistoryRepository()
        val (repo, _) = repository(gated, history, concurrent = 1)

        val id1 = repo.enqueue("https://example.com/a.mp4")
        advanceUntilIdle()
        assertTrue(gated.firstEntered.isCompleted) // first holds the permit

        val id2 = repo.enqueue("https://example.com/b.mp4")

        // Cancel the waiting item, then let the first finish.
        repo.cancel(id2)
        gated.releaseFirst.complete(Unit)
        advanceUntilIdle()

        assertEquals(DownloadStatus.COMPLETED, history.get(id1)!!.status)
        assertEquals(DownloadStatus.CANCELLED, history.get(id2)!!.status)
    }

    // ===== Phase 7: retry / remove / bulk removal / recovery =====

    @Test
    fun `retry requeues a failed download with a fresh id`() = testScope.runTest {
        val downloader = RecordingDownloader().apply { failWith = DownloadException.Http(404) }
        val history = FakeHistoryRepository()
        val (repo, _) = repository(downloader, history)

        val id1 = repo.enqueue("https://example.com/x.mp4")
        advanceUntilIdle()
        assertEquals(DownloadStatus.FAILED, history.get(id1)!!.status)

        downloader.failWith = null // retry succeeds
        val id2 = repo.retry(id1)
        advanceUntilIdle()

        assertNotNull("retry must return the new history id", id2)
        assertTrue("fresh id for the retried row", id2!! > id1)
        assertEquals(DownloadStatus.COMPLETED, history.get(id2)!!.status)
        assertNull("failed row must be deleted by retry", history.get(id1))
        assertEquals(1, history.rows.value.size)
    }

    @Test
    fun `retry requeues a cancelled download with a fresh id`() = testScope.runTest {
        // Phase 9 pins the CANCELLED retry path: the UI now offers retry on
        // cancelled rows, and the repository must honour it exactly like a
        // failed one — fresh id, old row deleted, clean lifecycle.
        val history = FakeHistoryRepository()
        val (repo, _) = repository(RecordingDownloader(), history)

        val id1 = repo.enqueue("https://example.com/c.mp4")
        // Cancel synchronously: the pump has not run yet, so the row leaves
        // the wait queue and lands CANCELLED without ever starting.
        repo.cancel(id1)
        advanceUntilIdle()

        assertEquals(DownloadStatus.CANCELLED, history.get(id1)!!.status)

        val id2 = repo.retry(id1)
        advanceUntilIdle()

        assertNotNull("cancelled rows are retryable", id2)
        assertTrue(id2!! > id1)
        assertEquals(DownloadStatus.COMPLETED, history.get(id2)!!.status)
        assertNull("cancelled row must be deleted by retry", history.get(id1))
        assertEquals(1, history.rows.value.size)
    }

    @Test
    fun `retry of an in-flight or completed row is rejected`() = testScope.runTest {
        val downloader = RecordingDownloader()
        val history = FakeHistoryRepository()
        val (repo, _) = repository(downloader, history)

        val id = repo.enqueue("https://example.com/x.mp4")
        advanceUntilIdle()
        assertEquals(DownloadStatus.COMPLETED, history.get(id)!!.status)

        assertNull("completed rows are not retryable", repo.retry(id))
        assertEquals("no duplicate job created", 1, history.rows.value.size)
    }

    @Test
    fun `retry missing row returns null`() = testScope.runTest {
        val history = FakeHistoryRepository()
        val (repo, _) = repository(RecordingDownloader(), history)
        assertNull(repo.retry(999))
    }

    @Test
    fun `concurrent double retry creates exactly one new job`() = testScope.runTest {
        // Two parallel retry() calls for the same failed row: the retryingIds
        // guard must let exactly one through.
        val downloader = RecordingDownloader().apply { failWith = DownloadException.Http(500) }
        val history = FakeHistoryRepository()
        val (repo, _) = repository(downloader, history)

        val id = repo.enqueue("https://example.com/x.mp4")
        advanceUntilIdle()
        assertEquals(DownloadStatus.FAILED, history.get(id)!!.status)

        downloader.failWith = null
        val first = testScope.async { repo.retry(id) }
        val second = testScope.async { repo.retry(id) }
        val results = listOf(first.await(), second.await())
        advanceUntilIdle()

        val newIds = results.filterNotNull()
        assertEquals("exactly one retry job", 1, newIds.size)
        assertEquals("history holds only the retried row", 1, history.rows.value.size)
    }

    @Test
    fun `remove completed deletes the published file and row`() = testScope.runTest {
        val downloader = RecordingDownloader()
        val history = FakeHistoryRepository()
        val (repo, _) = repository(downloader, history)

        val id = repo.enqueue("https://example.com/x.mp4")
        advanceUntilIdle()
        assertEquals(DownloadStatus.COMPLETED, history.get(id)!!.status)

        assertTrue(repo.remove(id))
        assertNull(history.get(id))
    }

    @Test
    fun `remove in-flight row is rejected`() = testScope.runTest {
        val gated = FirstHopGatedDownloader()
        val history = FakeHistoryRepository()
        val (repo, _) = repository(gated, history, concurrent = 1)

        val id = repo.enqueue("https://example.com/x.mp4")
        advanceUntilIdle()
        assertTrue(gated.firstEntered.isCompleted) // DOWNLOADING, holds permit

        assertFalse("in-flight rows must be cancelled, not removed", repo.remove(id))
        assertEquals(DownloadStatus.DOWNLOADING, history.get(id)!!.status)

        gated.releaseFirst.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `remove queued row is rejected`() = testScope.runTest {
        val gated = FirstHopGatedDownloader()
        val history = FakeHistoryRepository()
        val (repo, _) = repository(gated, history, concurrent = 1)

        repo.enqueue("https://example.com/a.mp4")
        advanceUntilIdle()
        val queued = repo.enqueue("https://example.com/b.mp4") // waits for permit
        // still QUEUED (not started)
        assertEquals(DownloadStatus.QUEUED, history.get(queued)!!.status)

        assertFalse(repo.remove(queued))
        gated.releaseFirst.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `remove completed bulk deletes rows and reports count`() = testScope.runTest {
        val downloader = RecordingDownloader()
        val history = FakeHistoryRepository()
        val (repo, _) = repository(downloader, history)

        repo.enqueue("https://example.com/a.mp4")
        repo.enqueue("https://example.com/b.mp4")
        advanceUntilIdle()
        assertEquals(2, history.rows.value.count { it.status == DownloadStatus.COMPLETED })

        val n = repo.removeCompleted()
        assertEquals(2, n)
        assertTrue(history.rows.value.isEmpty())
    }

    @Test
    fun `remove failed bulk deletes failed and cancelled rows only`() = testScope.runTest {
        val downloader = RecordingDownloader().apply { failWith = DownloadException.Http(404) }
        val history = FakeHistoryRepository()
        val (repo, _) = repository(downloader, history)

        repo.enqueue("https://example.com/fail.mp4") // → FAILED
        advanceUntilIdle()
        downloader.failWith = null
        repo.enqueue("https://example.com/ok.mp4") // → COMPLETED
        advanceUntilIdle()
        assertEquals(2, history.rows.value.size)

        val n = repo.removeFailed()
        assertEquals(1, n)
        assertEquals(
            "only the completed row remains",
            listOf(DownloadStatus.COMPLETED),
            history.rows.value.map { it.status },
        )
    }

    @Test
    fun `recover interrupted moves stuck rows to failed`() = testScope.runTest {
        val history = FakeHistoryRepository()
        // Simulate rows a previous process left behind: insert directly, with
        // createdAt BACKDATED before this repository instance was constructed
        // (its process-start boundary must exclude current-process rows).
        val previousProcess = Instant.now().minusSeconds(3600)
        val stuck1 = history.add(
            HistoryItem(
                sourceUrl = "https://example.com/a.mp4",
                title = "a.mp4",
                status = DownloadStatus.DOWNLOADING,
                createdAt = previousProcess,
            ),
        )
        val stuck2 = history.add(
            HistoryItem(
                sourceUrl = "https://example.com/b.mp4",
                title = "b.mp4",
                status = DownloadStatus.QUEUED,
                createdAt = previousProcess,
            ),
        )
        val done = history.add(
            HistoryItem(
                sourceUrl = "https://example.com/c.mp4",
                title = "c.mp4",
                status = DownloadStatus.COMPLETED,
                createdAt = previousProcess,
            ),
        )
        val (repo, _) = repository(RecordingDownloader(), history)

        val recovered = repo.recoverInterrupted()
        advanceUntilIdle()

        assertEquals(2, recovered)
        assertEquals(DownloadStatus.FAILED, history.get(stuck1)!!.status)
        assertEquals(DownloadStatus.FAILED, history.get(stuck2)!!.status)
        assertEquals(DownloadStatus.COMPLETED, history.get(done)!!.status)
        assertTrue(
            "recovery reason is user-readable, not a stack trace",
            (history.get(stuck1)!!.errorMessage ?: "").contains("Interrupted"),
        )
    }

    @Test
    fun `recover interrupted with clean history is a no-op`() = testScope.runTest {
        val history = FakeHistoryRepository()
        val (repo, _) = repository(RecordingDownloader(), history)
        assertEquals(0, repo.recoverInterrupted())
    }

    @Test
    fun `row created during the current process is never recovered`() = testScope.runTest {
        // Fix 3: a download enqueued AFTER the repository was constructed
        // (i.e. during this process) has a live job — recovery must not
        // touch it even if it is still QUEUED when recovery scans.
        val gated = FirstHopGatedDownloader() // first download hangs, holds the permit
        val history = FakeHistoryRepository()
        val (repo, _) = repository(gated, history, concurrent = 1)

        val current = repo.enqueue("https://example.com/current.mp4") // createdAt = now
        advanceUntilIdle()
        assertTrue(gated.firstEntered.isCompleted)
        assertEquals(DownloadStatus.DOWNLOADING, history.get(current)!!.status)

        assertEquals("current-process row must be left alone", 0, repo.recoverInterrupted())
        assertEquals(DownloadStatus.DOWNLOADING, history.get(current)!!.status)

        gated.releaseFirst.complete(Unit)
        advanceUntilIdle()
        assertEquals(DownloadStatus.COMPLETED, history.get(current)!!.status)
    }

    @Test
    fun `recovery boundary still recovers pre-process rows`() = testScope.runTest {
        // Fix 3 companion: a backdated (previous-process) QUEUED row IS
        // recovered even while a current-process download runs.
        val gated = FirstHopGatedDownloader()
        val history = FakeHistoryRepository()
        val (repo, _) = repository(gated, history, concurrent = 1)

        history.add(
            HistoryItem(
                sourceUrl = "https://example.com/old.mp4",
                title = "old.mp4",
                status = DownloadStatus.QUEUED,
                createdAt = Instant.now().minusSeconds(3600),
            ),
        )
        val current = repo.enqueue("https://example.com/current.mp4")
        advanceUntilIdle()
        assertTrue(gated.firstEntered.isCompleted)

        val recovered = repo.recoverInterrupted()
        assertEquals(1, recovered)
        assertEquals(DownloadStatus.DOWNLOADING, history.get(current)!!.status)

        gated.releaseFirst.complete(Unit)
        advanceUntilIdle()
        assertEquals(DownloadStatus.COMPLETED, history.get(current)!!.status)
    }

    // ===== Phase 10: history / local-media management =====

    @Test
    fun `completion persists the server-declared mime type`() = testScope.runTest {
        val history = FakeHistoryRepository()
        val (repo, _) = repository(RecordingDownloader(), history)

        val id = repo.enqueue("https://example.com/a.mp4")
        advanceUntilIdle()

        assertEquals("video/mp4", history.mimeUpdates[id])
        assertEquals("video/mp4", history.get(id)!!.mimeType)
    }

    @Test
    fun `persisted mime type is normalized like phase 8 header parsing`() = testScope.runTest {
        // Phase 10 fix round: parameterized/uppercase raw Content-Type forms
        // must not be stored raw.
        val history = FakeHistoryRepository()
        val (repo, _) = repository(
            RecordingDownloader().apply { responseMime = "VIDEO/MP4; charset=binary" },
            history,
        )

        val id = repo.enqueue("https://example.com/a.mp4")
        advanceUntilIdle()

        assertEquals("video/mp4", history.mimeUpdates[id])
        assertEquals("video/mp4", history.get(id)!!.mimeType)
    }

    @Test
    fun `blank mime type persists as null, never a guess`() = testScope.runTest {
        val history = FakeHistoryRepository()
        val (repo, _) = repository(
            RecordingDownloader().apply { responseMime = "   " },
            history,
        )

        val id = repo.enqueue("https://example.com/a.mp4")
        advanceUntilIdle()

        assertTrue(history.mimeUpdates.containsKey(id))
        assertNull(history.mimeUpdates[id])
        assertNull(history.get(id)!!.mimeType)
    }

    @Test
    fun `removeRecord deletes a completed record without touching its file`() = testScope.runTest {
        val history = FakeHistoryRepository()
        val (repo, writer, _) = repositoryFull(RecordingDownloader(), history)

        val id = repo.enqueue("https://example.com/a.mp4")
        advanceUntilIdle()
        assertEquals(DownloadStatus.COMPLETED, history.get(id)!!.status)
        assertTrue(writer.deleteCalls.isEmpty())

        assertTrue(repo.removeRecord(id))
        advanceUntilIdle()

        assertNull("record must be removed from history", history.get(id))
        assertTrue(
            "record-only removal must NEVER delete the published file",
            writer.deleteCalls.isEmpty(),
        )
    }

    @Test
    fun `repeated removeRecord calls are safe and stay record-only`() = testScope.runTest {
        // PHASE 14: the second call finds no row — false, no file deletion,
        // no crash (repeated taps / stale UI must be harmless).
        val history = FakeHistoryRepository()
        val (repo, writer, _) = repositoryFull(RecordingDownloader(), history)

        val id = repo.enqueue("https://example.com/a.mp4")
        advanceUntilIdle()

        assertTrue(repo.removeRecord(id))
        assertFalse(repo.removeRecord(id))
        assertFalse("missing rows are not removable", repo.removeRecord(999))
        assertTrue(writer.deleteCalls.isEmpty())
    }

    @Test
    fun `removeRecord removes failed and cancelled rows but rejects in-flight rows`() =
        testScope.runTest {
            // FAILED row:
            val failedHistory = FakeHistoryRepository()
            val (failedRepo, _) = repository(
                RecordingDownloader().apply { failWith = DownloadException.Http(500) },
                failedHistory,
            )
            val failedId = failedRepo.enqueue("https://example.com/f.mp4")
            advanceUntilIdle()
            assertEquals(DownloadStatus.FAILED, failedHistory.get(failedId)!!.status)
            assertTrue(failedRepo.removeRecord(failedId))
            assertNull(failedHistory.get(failedId))

            // CANCELLED and in-flight rows:
            val gated = FirstHopGatedDownloader()
            val history = FakeHistoryRepository()
            val (repo, _, _) = repositoryFull(gated, history, concurrent = 1)
            val inFlightId = repo.enqueue("https://example.com/live.mp4")
            advanceUntilIdle()
            assertTrue(gated.firstEntered.isCompleted) // DOWNLOADING, holds permit
            val cancelledId = repo.enqueue("https://example.com/gone.mp4")
            repo.cancel(cancelledId) // still waiting → CANCELLED

            assertFalse("in-flight rows must be cancelled first", repo.removeRecord(inFlightId))
            assertNotNull(history.get(inFlightId))
            assertTrue(repo.removeRecord(cancelledId))
            assertNull(history.get(cancelledId))

            gated.releaseFirst.complete(Unit)
            advanceUntilIdle()
        }

    @Test
    fun `mediaAvailability reports available, missing and not-applicable honestly`() =
        testScope.runTest {
            val history = FakeHistoryRepository()
            val (repo, _, checker) = repositoryFull(RecordingDownloader(), history)

            val id = repo.enqueue("https://example.com/a.mp4")
            advanceUntilIdle()
            assertEquals(DownloadStatus.COMPLETED, history.get(id)!!.status)

            checker.result = true
            assertEquals(MediaFileAvailability.AVAILABLE, repo.mediaAvailability(id))
            checker.result = false
            assertEquals(MediaFileAvailability.UNAVAILABLE, repo.mediaAvailability(id))
            // The checker saw the row's real persisted URI, nothing else.
            assertEquals(2, checker.checked.size)
            assertEquals(history.get(id)!!.localUri, checker.checked.last())

            // Unknown row / non-completed rows are NOT_APPLICABLE, never
            // reported as a missing file.
            assertEquals(MediaFileAvailability.NOT_APPLICABLE, repo.mediaAvailability(999))
        }

    @Test
    fun `openableMedia returns validated media only when the file resolves`() =
        testScope.runTest {
            val history = FakeHistoryRepository()
            val (repo, _, checker) = repositoryFull(RecordingDownloader(), history)

            val id = repo.enqueue("https://example.com/a.mp4")
            advanceUntilIdle()

            checker.result = true
            val media = repo.openableMedia(id)
            assertEquals(history.get(id)!!.localUri, media?.uriText)
            assertEquals("video/mp4", media?.mimeType)

            checker.result = false
            assertNull("a missing file is never openable", repo.openableMedia(id))
        }
}
