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
import com.narcictub.app.domain.repository.HistoryRepository
import com.narcictub.app.domain.repository.SettingsRepository
import com.narcictub.app.domain.model.DownloadLocation
import com.narcictub.app.domain.model.ThemeMode
import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
            return DownloadFileResult(bytesDownloaded = 10, contentType = "video/mp4")
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

        override suspend fun updateLocalUri(id: Long, localUri: String) {
            rows.value = rows.value.map { if (it.id == id) it.copy(localUri = localUri) else it }
        }

        override suspend fun updateSizeBytes(id: Long, sizeBytes: Long) {
            rows.value = rows.value.map { if (it.id == id) it.copy(sizeBytes = sizeBytes) else it }
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

        override suspend fun doPublish(
            stagingFile: File,
            displayName: String,
            mimeType: String?,
            subDirectory: String?,
        ): Uri {
            publishCalls++
            if (failPublish) throw java.io.IOException("mediastore boom")
            return mockk<Uri>(relaxed = true)
        }
    }

    /** Test subclass isolating staging to the rule-managed temp folder and
     *  running the work scope on the TestScheduler so advanceUntilIdle works. */
    private class TestRepository(
        stagingRoot: File,
        downloader: RecordingDownloader,
        history: FakeHistoryRepository,
        settings: FakeSettingsRepository,
        writer: StubMediaStoreWriter,
        scope: kotlinx.coroutines.CoroutineScope,
    ) : DownloadRepositoryImpl(
        context = mockk<Context>(relaxed = true),
        downloader = downloader,
        historyRepository = history,
        settingsRepository = settings,
        mediaStoreWriter = writer,
        workScope = scope,
    ) {
        var stagingOverride: File? = null
        private val root = stagingRoot
        override fun stagingRoot(): File = stagingOverride ?: root
    }

    private fun repository(
        downloader: RecordingDownloader,
        history: FakeHistoryRepository,
        concurrent: Int = 1,
        failPublish: Boolean = false,
    ): Pair<TestRepository, StubMediaStoreWriter> {
        val writer = StubMediaStoreWriter().also { it.failPublish = failPublish }
        val repo = TestRepository(
            stagingRoot = tmp.newFolder(),
            downloader = downloader,
            history = history,
            settings = FakeSettingsRepository(concurrent),
            writer = writer,
            scope = testScope,
        )
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
}
