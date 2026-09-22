package com.narcictub.app.domain.usecase

import com.narcictub.app.domain.repository.DownloadRepository
import com.narcictub.app.domain.repository.HistoryRepository
import com.narcictub.app.domain.repository.SettingsRepository
import com.narcictub.app.domain.model.AppSettings
import com.narcictub.app.domain.model.DownloadStatus
import com.narcictub.app.domain.model.HistoryItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant

class EnqueueDownloadUseCaseTest {

    private class FakeDownloadRepository(
        private val history: HistoryRepository,
    ) : DownloadRepository {
        val enqueued = mutableListOf<String>()
        var failWith: Exception? = null

        override val progress = MutableStateFlow<Map<Long, com.narcictub.app.domain.model.DownloadProgress>>(emptyMap())
        override fun observeDownloads(): Flow<List<HistoryItem>> = history.observeHistory()

        override suspend fun enqueue(sourceUrl: String, durationSeconds: Long?): Long {
            failWith?.let { throw it }
            enqueued.add(sourceUrl)
            return history.add(
                HistoryItem(sourceUrl = sourceUrl, title = "t", createdAt = Instant.now()),
            )
        }

        val titledMimeTypes = mutableListOf<String?>()
        override suspend fun enqueueTitled(
            sourceUrl: String,
            durationSeconds: Long?,
            title: String?,
            mimeType: String?,
        ): Long {
            titledMimeTypes.add(mimeType)
            return enqueue(sourceUrl, durationSeconds)
        }

        override suspend fun cancel(id: Long) {}
        override suspend fun retry(id: Long): Long? = null
        override suspend fun remove(id: Long): Boolean = false
        override suspend fun removeCompleted(): Int = 0
        override suspend fun removeFailed(): Int = 0
        override suspend fun recoverInterrupted(): Int = 0
        override suspend fun mediaAvailability(id: Long) =
            com.narcictub.app.domain.model.MediaFileAvailability.NOT_APPLICABLE
        override suspend fun openableMedia(id: Long) = null
        override suspend fun removeRecord(id: Long): Boolean = false
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
            rows.value = rows.value.map { if (it.id == id) it.copy(status = status) else it }
        }
        override suspend fun delete(id: Long) {}
        override suspend fun clear() {}

        override suspend fun deleteByStatuses(statuses: Set<DownloadStatus>) {
            rows.value = rows.value.filterNot { it.status in statuses }
        }
        override suspend fun updateLocalUri(id: Long, localUri: String) {}
        override suspend fun updateSizeBytes(id: Long, sizeBytes: Long) {}
        override suspend fun updateMimeType(id: Long, mimeType: String?) {}
    }

    @Test
    fun `a resolved mime type is threaded through to enqueueTitled`() = runTest {
        val history = FakeHistoryRepository()
        val repo = FakeDownloadRepository(history)
        val useCase = EnqueueDownloadUseCase(repo)

        useCase("https://example.com/clip.mp4", mimeType = "video/mp4; codecs=avc1")

        assertEquals(listOf("video/mp4; codecs=avc1"), repo.titledMimeTypes)
    }

    @Test
    fun `no mime type keeps the plain enqueue path`() = runTest {
        val history = FakeHistoryRepository()
        val repo = FakeDownloadRepository(history)
        val useCase = EnqueueDownloadUseCase(repo)

        useCase("https://example.com/clip.mp4")

        assertTrue(repo.titledMimeTypes.isEmpty())
        assertEquals(listOf("https://example.com/clip.mp4"), repo.enqueued)
    }

    @Test
    fun `valid url is normalized and queued`() = runTest {
        val history = FakeHistoryRepository()
        val repo = FakeDownloadRepository(history)
        val useCase = EnqueueDownloadUseCase(repo)

        val result = useCase("  https://example.com/file.mp4  ")

        assertTrue(result.isSuccess)
        assertEquals(listOf("https://example.com/file.mp4"), repo.enqueued)
        assertTrue(result.getOrThrow()!! > 0)
    }

    @Test
    fun `invalid url never reaches repository`() = runTest {
        val history = FakeHistoryRepository()
        val repo = FakeDownloadRepository(history)
        val useCase = EnqueueDownloadUseCase(repo)

        val result = useCase("not a url")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is InvalidUrlException)
        assertEquals(0, repo.enqueued.size)
    }

    @Test
    fun `credential url never reaches repository`() = runTest {
        val history = FakeHistoryRepository()
        val repo = FakeDownloadRepository(history)
        val useCase = EnqueueDownloadUseCase(repo)

        val result = useCase("https://user:pass@example.com/f.mp4")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is InvalidUrlException)
        assertEquals(0, repo.enqueued.size)
    }

    @Test
    fun `repository failure is reported as failure not crash`() = runTest {
        val history = FakeHistoryRepository()
        val repo = FakeDownloadRepository(history).apply { failWith = IOException("db down") }
        val useCase = EnqueueDownloadUseCase(repo)

        val result = useCase("https://example.com/file.mp4")

        assertTrue(result.isFailure)
        assertEquals(0, repo.enqueued.size) // enqueue threw before recording
    }

    // ===== Phase 20: stage-1 destination policy at the enqueue boundary =====

    @Test
    fun `loopback destination is rejected before queueing`() = runTest {
        val history = FakeHistoryRepository()
        val repo = FakeDownloadRepository(history)
        val useCase = EnqueueDownloadUseCase(repo)

        val result = useCase("http://127.0.0.1/secret.mp4")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is InvalidUrlException)
        assertTrue(
            (result.exceptionOrNull() as InvalidUrlException).message!!.contains("blocked destination"),
        )
        assertEquals("nothing may reach the repository", 0, repo.enqueued.size)
    }

    @Test
    fun `private literal destination is rejected before queueing`() = runTest {
        val history = FakeHistoryRepository()
        val repo = FakeDownloadRepository(history)
        val useCase = EnqueueDownloadUseCase(repo)

        val result = useCase("https://192.168.1.10/media.mp4")

        assertTrue(result.isFailure)
        assertEquals(0, repo.enqueued.size)
    }

    @Test
    fun `metadata service destination is rejected before queueing`() = runTest {
        val history = FakeHistoryRepository()
        val repo = FakeDownloadRepository(history)
        val useCase = EnqueueDownloadUseCase(repo)

        val result = useCase("http://169.254.169.254/latest/meta-data")

        assertTrue(result.isFailure)
        assertEquals(0, repo.enqueued.size)
    }

    @Test
    fun `public https destination still queues normally`() = runTest {
        val history = FakeHistoryRepository()
        val repo = FakeDownloadRepository(history)
        val useCase = EnqueueDownloadUseCase(repo)

        val result = useCase("https://cdn.example.com/video.mp4")

        assertTrue("public destinations must not be affected by the policy gate", result.isSuccess)
        assertEquals(listOf("https://cdn.example.com/video.mp4"), repo.enqueued)
    }
}
