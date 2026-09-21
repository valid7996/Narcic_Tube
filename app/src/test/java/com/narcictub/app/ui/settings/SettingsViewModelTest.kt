package com.narcictub.app.ui.settings

import com.narcictub.app.domain.model.AppSettings
import com.narcictub.app.domain.model.DownloadLocation
import com.narcictub.app.domain.model.ThemeMode
import com.narcictub.app.domain.repository.SettingsRepository
import com.narcictub.app.domain.usecase.ObserveSettingsUseCase
import com.narcictub.app.domain.usecase.SetConcurrentDownloadsUseCase
import com.narcictub.app.domain.usecase.SetDownloadLocationUseCase
import com.narcictub.app.domain.usecase.SetThemeModeUseCase
import java.io.IOException
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
 * PHASE 13 — SettingsViewModel behavior: initial loading, real values from
 * the repository flow, reactive updates, safe load/write failures and
 * recovery. The state must never leak raw exception details.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private class FakeSettingsRepository : SettingsRepository {
        val state = MutableStateFlow(AppSettings())
        var failReads = false
        var failWrites = false

        val themeWrites = mutableListOf<ThemeMode>()
        val locationWrites = mutableListOf<DownloadLocation>()
        val concurrentWrites = mutableListOf<Int>()

        override val settings: kotlinx.coroutines.flow.Flow<AppSettings>
            get() = if (failReads) {
                flow { throw IOException("corrupt datastore") }
            } else {
                state
            }

        override suspend fun setTheme(mode: ThemeMode) {
            if (failWrites) throw IOException("boom")
            themeWrites.add(mode)
            state.value = state.value.copy(theme = mode)
        }

        override suspend fun setDownloadLocation(location: DownloadLocation) {
            if (failWrites) throw IOException("boom")
            locationWrites.add(location)
            state.value = state.value.copy(downloadLocation = location)
        }

        override suspend fun setWifiOnly(enabled: Boolean) {}
        override suspend fun setNotificationsEnabled(enabled: Boolean) {}

        override suspend fun setConcurrentDownloads(count: Int) {
            if (failWrites) throw IOException("boom")
            concurrentWrites.add(count)
            state.value = state.value.copy(concurrentDownloads = count)
        }
    }

    private lateinit var repository: FakeSettingsRepository

    private fun viewModel() = SettingsViewModel(
        observeSettings = ObserveSettingsUseCase(repository),
        setThemeMode = SetThemeModeUseCase(repository),
        setDownloadLocation = SetDownloadLocationUseCase(repository),
        setConcurrentDownloads = SetConcurrentDownloadsUseCase(repository),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        repository = FakeSettingsRepository()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** WhileSubscribed needs a live collector for the upstream to run. */
    private fun collect(vm: SettingsViewModel): kotlinx.coroutines.Job =
        kotlinx.coroutines.CoroutineScope(testDispatcher + kotlinx.coroutines.Job())
            .launch { vm.uiState.collect {} }

    private fun collectTheme(vm: SettingsViewModel): kotlinx.coroutines.Job =
        kotlinx.coroutines.CoroutineScope(testDispatcher + kotlinx.coroutines.Job())
            .launch { vm.themeMode.collect {} }

    @Test
    fun `initial state is loading`() {
        val vm = viewModel()
        assertTrue("no snapshot yet — must show loading", vm.uiState.value.isLoading)
    }

    @Test
    fun `successful load exposes the real persisted values`() = runTest {
        repository.state.value = AppSettings(
            theme = ThemeMode.DARK,
            downloadLocation = DownloadLocation.MUSIC,
            concurrentDownloads = 5,
        )
        val vm = viewModel()
        val job = collect(vm)
        advanceUntilIdle()
        job.cancel()

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertEquals(ThemeMode.DARK, state.themeMode)
        assertEquals(DownloadLocation.MUSIC, state.downloadLocation)
        assertEquals(5, state.concurrentDownloads)
        assertNull(state.errorMessage)
    }

    @Test
    fun `theme selection writes through and re-renders`() = runTest {
        val vm = viewModel()
        val job = collect(vm)
        val themeJob = collectTheme(vm)
        advanceUntilIdle()

        vm.onThemeModeSelected(ThemeMode.LIGHT)
        advanceUntilIdle()

        assertEquals(listOf(ThemeMode.LIGHT), repository.themeWrites)
        assertEquals(ThemeMode.LIGHT, vm.uiState.value.themeMode)
        assertNull(vm.uiState.value.errorMessage)
        // The theme projection (used by the app shell) follows too.
        assertEquals(ThemeMode.LIGHT, vm.themeMode.value)
        job.cancel()
        themeJob.cancel()
    }

    @Test
    fun `download location selection writes through and re-renders`() = runTest {
        val vm = viewModel()
        val job = collect(vm)
        advanceUntilIdle()

        vm.onDownloadLocationSelected(DownloadLocation.DCIM)
        advanceUntilIdle()

        assertEquals(listOf(DownloadLocation.DCIM), repository.locationWrites)
        assertEquals(DownloadLocation.DCIM, vm.uiState.value.downloadLocation)
        job.cancel()
    }

    @Test
    fun `valid concurrent selection writes through`() = runTest {
        val vm = viewModel()
        val job = collect(vm)
        advanceUntilIdle()

        vm.onConcurrentDownloadsSelected(6)
        advanceUntilIdle()

        assertEquals(listOf(6), repository.concurrentWrites)
        assertEquals(6, vm.uiState.value.concurrentDownloads)
        assertNull(vm.uiState.value.errorMessage)
        job.cancel()
    }

    @Test
    fun `out-of-range concurrent value never reaches the repository`() = runTest {
        val vm = viewModel()
        val job = collect(vm)
        advanceUntilIdle()

        vm.onConcurrentDownloadsSelected(0)
        vm.onConcurrentDownloadsSelected(9)
        advanceUntilIdle()

        assertEquals("out-of-range must be gated at the use case", 0, repository.concurrentWrites.size)
        assertNotNull(vm.uiState.value.errorMessage)
    }

    @Test
    fun `load failure shows a safe message with usable defaults`() = runTest {
        repository.failReads = true
        val vm = viewModel()
        val job = collect(vm)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse("load failure must not hang in loading", state.isLoading)
        assertEquals("Settings couldn't be loaded. Showing defaults.", state.errorMessage)
        assertFalse(state.errorMessage!!.contains("corrupt datastore"))
        assertEquals("fallback values keep the screen usable", ThemeMode.SYSTEM, state.themeMode)
        job.cancel()
    }

    @Test
    fun `write failure surfaces a safe message and later recovery clears it`() = runTest {
        val vm = viewModel()
        val job = collect(vm)
        advanceUntilIdle()

        repository.failWrites = true
        vm.onThemeModeSelected(ThemeMode.DARK)
        advanceUntilIdle()
        assertEquals("Couldn't save that setting. Try again.", vm.uiState.value.errorMessage)
        assertFalse(vm.uiState.value.errorMessage!!.contains("boom"))

        // Recovery: the next successful write clears the error.
        repository.failWrites = false
        vm.onThemeModeSelected(ThemeMode.LIGHT)
        advanceUntilIdle()
        assertNull(vm.uiState.value.errorMessage)
        assertEquals(ThemeMode.LIGHT, vm.uiState.value.themeMode)
        job.cancel()
    }

    @Test
    fun `theme mode projection defaults to system while loading`() {
        val vm = viewModel()
        assertEquals(ThemeMode.SYSTEM, vm.themeMode.value)
    }
}
