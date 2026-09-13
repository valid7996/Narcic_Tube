package com.narcictub.app.data.history

import com.narcictub.app.domain.model.DownloadStatus
import com.narcictub.app.domain.model.HistoryItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

import kotlinx.coroutines.flow.first

import com.narcictub.app.data.history.HistoryMappers.toDomain
import com.narcictub.app.data.history.HistoryMappers.toEntity

/**
 * Repository behavior test against an in-memory HistoryDao fake. Verifies
 * the HistoryRepositoryImpl contract (ordering, status updates, deletes)
 * without touching a real database.
 */
class HistoryRepositoryImplTest {

    private class FakeHistoryDao(initial: List<HistoryEntity> = emptyList()) : HistoryDao {
        private val rows = MutableStateFlow(initial.toMutableList().apply { forEachIndexed { i, e -> if (e.id == 0L) this[i] = e.copy(id = i + 1L) } })
        private var nextId = (initial.maxOfOrNull { it.id } ?: 0L) + 1L

        override suspend fun insert(entity: HistoryEntity): Long {
            val id = if (entity.id != 0L) entity.id else nextId++
            rows.value = rows.value.apply { removeAll { it.id == id }; add(entity.copy(id = id)) }
            return id
        }

        override fun observeAll(): Flow<List<HistoryEntity>> =
            rows.map { list -> list.sortedWith(compareByDescending<HistoryEntity> { it.createdAtEpochMs }.thenByDescending { it.id }) }

        override suspend fun getById(id: Long): HistoryEntity? = rows.value.firstOrNull { it.id == id }

        override suspend fun updateStatus(id: Long, status: String, errorMessage: String?, completedAtEpochMs: Long?) {
            rows.value = rows.value.map {
                if (it.id == id) it.copy(status = status, errorMessage = errorMessage, completedAtEpochMs = completedAtEpochMs) else it
            }.toMutableList()
        }

        override suspend fun delete(entity: HistoryEntity) = deleteById(entity.id)

        override suspend fun deleteById(id: Long) {
            rows.value = rows.value.filterNot { it.id == id }.toMutableList()
        }

        override suspend fun clear() {
            rows.value = mutableListOf()
        }
    }

    private lateinit var repo: HistoryRepositoryImpl
    private lateinit var dao: FakeHistoryDao

    private fun item(url: String, title: String, createdAtMs: Long = 1L) = HistoryItem(
        sourceUrl = url,
        title = title,
        createdAt = Instant.ofEpochMilli(createdAtMs),
    )

    @Before
    fun setUp() {
        dao = FakeHistoryDao()
        repo = HistoryRepositoryImpl(dao)
    }

    @Test
    fun `add assigns id and item is retrievable`() = runTest {
        val id = repo.add(item("https://example.com/a", "Alpha"))
        assertTrue(id > 0)
        val loaded = repo.get(id)
        assertNotNull(loaded)
        assertEquals("Alpha", loaded!!.title)
    }

    @Test
    fun `observeHistory emits newest first`() = runTest {
        repo.add(item("https://example.com/old", "Old", createdAtMs = 1L))
        repo.add(item("https://example.com/new", "New", createdAtMs = 2L))
        val list = repo.observeHistory()
        val names = list.first().map { it.title }
        assertEquals(listOf("New", "Old"), names)
    }

    @Test
    fun `updateStatus persists status error and completion time`() = runTest {
        val id = repo.add(item("https://example.com/a", "Alpha"))
        val completed = Instant.ofEpochMilli(500L)
        repo.updateStatus(id, DownloadStatus.COMPLETED, completedAt = completed)
        val updated = repo.get(id)!!
        assertEquals(DownloadStatus.COMPLETED, updated.status)
        assertEquals(completed, updated.completedAt)

        repo.updateStatus(id, DownloadStatus.FAILED, errorMessage = "boom")
        val failed = repo.get(id)!!
        assertEquals(DownloadStatus.FAILED, failed.status)
        assertEquals("boom", failed.errorMessage)
    }

    @Test
    fun `delete removes exactly one row`() = runTest {
        val a = repo.add(item("https://example.com/a", "A"))
        val b = repo.add(item("https://example.com/b", "B"))
        repo.delete(a)
        assertNull(repo.get(a))
        assertNotNull(repo.get(b))
    }

    @Test
    fun `clear removes everything`() = runTest {
        repo.add(item("https://example.com/a", "A"))
        repo.add(item("https://example.com/b", "B"))
        repo.clear()
        val list = repo.observeHistory().first()
        assertTrue(list.isEmpty())
    }

    @Test
    fun `round-trips through mappers keep domain values`() = runTest {
        val id = repo.add(
            item("https://example.com/a", "Alpha").copy(status = DownloadStatus.DOWNLOADING),
        )
        assertEquals(DownloadStatus.DOWNLOADING, repo.get(id)!!.status)
    }
}
