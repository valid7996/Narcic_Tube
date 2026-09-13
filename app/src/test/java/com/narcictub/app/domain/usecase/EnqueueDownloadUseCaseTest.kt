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

        override suspend fun enqueue(sourceUrl: String): Long {
            failWith?.let { throw it }
            enqueued.add(sourceUrl)
            return history.add(
                HistoryItem(sourceUrl = sourceUrl, title = "t", createdAt = Instant.now()),
            )
        }

        override suspend fun cancel(id: Long) {}
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
        override suspend fun updateLocalUri(id: Long, localUri: String) {}
        override suspend fun updateSizeBytes(id: Long, sizeBytes: Long) {}
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
}
