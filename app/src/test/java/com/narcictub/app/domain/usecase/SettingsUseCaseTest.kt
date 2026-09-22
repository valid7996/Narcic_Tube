package com.narcictub.app.domain.usecase

import com.narcictub.app.domain.model.AppSettings
import com.narcictub.app.domain.model.DownloadLocation
import com.narcictub.app.domain.model.ThemeMode
import com.narcictub.app.domain.repository.SettingsRepository
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 13 — settings use cases: the concurrent-downloads range gate keeps
 * out-of-range values away from the repository/DataStore entirely; the
 * other setters delegate to the repository and map failures to Result.
 */
class SettingsUseCaseTest {

    private class RecordingSettingsRepository : SettingsRepository {
        val themeWrites = mutableListOf<ThemeMode>()
        val locationWrites = mutableListOf<DownloadLocation>()
        val concurrentWrites = mutableListOf<Int>()
        var fail = false

        override val settings = kotlinx.coroutines.flow.MutableStateFlow(AppSettings())

        override suspend fun setTheme(mode: ThemeMode) {
            if (fail) throw IOException("boom")
            themeWrites.add(mode)
        }
        override suspend fun setDownloadLocation(location: DownloadLocation) {
            if (fail) throw IOException("boom")
            locationWrites.add(location)
        }
        override suspend fun setWifiOnly(enabled: Boolean) {
            if (fail) throw IOException("boom")
        }
        override suspend fun setConcurrentDownloads(count: Int) {
            if (fail) throw IOException("boom")
            concurrentWrites.add(count)
        }
        override suspend fun setNotificationsEnabled(enabled: Boolean) {
            if (fail) throw IOException("boom")
        }
        val clipboardWatcherWrites = mutableListOf<Boolean>()
        override suspend fun setClipboardWatcherEnabled(enabled: Boolean) {
            if (fail) throw IOException("boom")
            clipboardWatcherWrites.add(enabled)
        }
    }

    private lateinit var repository: RecordingSettingsRepository

    private fun useCases(): Quadruple {
        repository = RecordingSettingsRepository()
        return Quadruple(
            SetThemeModeUseCase(repository),
            SetDownloadLocationUseCase(repository),
            SetConcurrentDownloadsUseCase(repository),
            SetClipboardWatcherEnabledUseCase(repository),
        )
    }

    private data class Quadruple(
        val theme: SetThemeModeUseCase,
        val location: SetDownloadLocationUseCase,
        val concurrent: SetConcurrentDownloadsUseCase,
        val clipboardWatcher: SetClipboardWatcherEnabledUseCase,
    )

    // ===== clipboard watcher toggle =====

    @Test
    fun `clipboard watcher enabled state is persisted as given`() = runTest {
        val (_, _, _, setClipboardWatcher) = useCases()

        assertTrue(setClipboardWatcher(true).isSuccess)
        assertTrue(setClipboardWatcher(false).isSuccess)

        assertEquals(listOf(true, false), repository.clipboardWatcherWrites)
    }

    @Test
    fun `clipboard watcher write failure is mapped to a Result failure`() = runTest {
        val (_, _, _, setClipboardWatcher) = useCases()
        repository.fail = true

        assertTrue(setClipboardWatcher(true).isFailure)
    }

    // ===== concurrent downloads: the range gate =====

    @Test
    fun `lower boundary value 1 reaches the repository`() = runTest {
        val (_, _, setConcurrent) = useCases()
        val result = setConcurrent(AppSettings.MIN_CONCURRENT_DOWNLOADS)
        assertTrue(result.isSuccess)
        assertEquals(listOf(1), repository.concurrentWrites)
    }

    @Test
    fun `upper boundary value 8 reaches the repository`() = runTest {
        val (_, _, setConcurrent) = useCases()
        val result = setConcurrent(AppSettings.MAX_CONCURRENT_DOWNLOADS)
        assertTrue(result.isSuccess)
        assertEquals(listOf(8), repository.concurrentWrites)
    }

    @Test
    fun `in-range interior values reach the repository`() = runTest {
        val (_, _, setConcurrent) = useCases()
        for (value in 2..7) {
            assertTrue(setConcurrent(value).isSuccess)
        }
        assertEquals(listOf(2, 3, 4, 5, 6, 7), repository.concurrentWrites)
    }

    @Test
    fun `below range is rejected and never reaches the repository`() = runTest {
        val (_, _, setConcurrent) = useCases()
        val result = setConcurrent(0)
        assertFalse(result.isSuccess)
        assertEquals(0, repository.concurrentWrites.size)
    }

    @Test
    fun `above range is rejected and never reaches the repository`() = runTest {
        val (_, _, setConcurrent) = useCases()
        val result = setConcurrent(9)
        assertFalse(result.isSuccess)
        assertEquals(0, repository.concurrentWrites.size)
    }

    @Test
    fun `negative values are rejected and never reach the repository`() = runTest {
        val (_, _, setConcurrent) = useCases()
        assertFalse(setConcurrent(-3).isSuccess)
        assertEquals(0, repository.concurrentWrites.size)
    }

    @Test
    fun `repository failure maps to Result failure`() = runTest {
        val (_, _, setConcurrent) = useCases()
        repository.fail = true
        val result = setConcurrent(4)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IOException)
    }

    // ===== theme and location delegation =====

    @Test
    fun `theme setter delegates to the repository`() = runTest {
        val (setTheme, _, _) = useCases()
        val result = setTheme(ThemeMode.DARK)
        assertTrue(result.isSuccess)
        assertEquals(listOf(ThemeMode.DARK), repository.themeWrites)
    }

    @Test
    fun `location setter delegates to the repository`() = runTest {
        val (_, setLocation, _) = useCases()
        val result = setLocation(DownloadLocation.MOVIES)
        assertTrue(result.isSuccess)
        assertEquals(listOf(DownloadLocation.MOVIES), repository.locationWrites)
    }

    @Test
    fun `theme repository failure maps to Result failure`() = runTest {
        val (setTheme, _, _) = useCases()
        repository.fail = true
        assertTrue(setTheme(ThemeMode.LIGHT).isFailure)
        assertEquals(0, repository.themeWrites.size)
    }
}
