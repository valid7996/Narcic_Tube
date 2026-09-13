package com.narcictub.app.ui.home

import app.cash.turbine.test
import com.narcictub.app.domain.model.MediaInfo
import com.narcictub.app.domain.resolver.MediaResolver
import com.narcictub.app.domain.resolver.ResolverNotImplementedException
import com.narcictub.app.domain.usecase.EnqueueDownloadUseCase
import com.narcictub.app.domain.usecase.ResolveUrlUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * HomeViewModel after the ARCH-1 fix: resolve flows through ResolveUrlUseCase
 * and queueing through EnqueueDownloadUseCase. The ViewModel holds no
 * independent business validation beyond UX feedback.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private class RecordingResolver : MediaResolver {
        var received: String? = null
        var result: Result<MediaInfo> = Result.failure(ResolverNotImplementedException())
        override suspend fun resolve(url: String): Result<MediaInfo> {
            received = url
            return result
        }
    }

    private class RecordingDownloadRepo : com.narcictub.app.domain.repository.DownloadRepository {
        val enqueued = mutableListOf<String>()
        var fail = false
        override val progress = kotlinx.coroutines.flow.MutableStateFlow<Map<Long, com.narcictub.app.domain.model.DownloadProgress>>(emptyMap())
        override fun observeDownloads() = kotlinx.coroutines.flow.MutableStateFlow<List<com.narcictub.app.domain.model.HistoryItem>>(emptyList())
        override suspend fun enqueue(sourceUrl: String): Long {
            if (fail) throw java.io.IOException("queue full")
            enqueued.add(sourceUrl)
            return enqueued.size.toLong()
        }
        override suspend fun cancel(id: Long) {}
    }

    private lateinit var resolver: RecordingResolver
    private lateinit var downloadRepo: RecordingDownloadRepo

    private fun viewModel() = HomeViewModel(
        resolveUrl = ResolveUrlUseCase(resolver),
        enqueueDownload = EnqueueDownloadUseCase(downloadRepo),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        resolver = RecordingResolver()
        downloadRepo = RecordingDownloadRepo()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is empty and invalid`() {
        val state = viewModel().uiState.value
        assertEquals("", state.url)
        assertFalse(state.isUrlValid)
        assertNull(state.resolvedHost)
    }

    @Test
    fun `valid url marks state valid for ux`() {
        val vm = viewModel()
        vm.onUrlChange("https://example.com/watch?v=1")
        assertTrue(vm.uiState.value.isUrlValid)
        assertNull(vm.uiState.value.validationMessage)
    }

    @Test
    fun `invalid url shows ux message`() {
        val vm = viewModel()
        vm.onUrlChange("ftp://example.com/x")
        assertFalse(vm.uiState.value.isUrlValid)
        assertEquals("Link must start with http:// or https://", vm.uiState.value.validationMessage)
    }

    @Test
    fun `resolve delegates to use case and surfaces host`() = runTest {
        resolver.result = Result.success(
            MediaInfo(sourceUrl = "https://example.com/x", title = "T", host = "example.com"),
        )
        val vm = viewModel()
        vm.onUrlChange("https://example.com/x")
        vm.onResolve()
        advanceUntilIdle()

        assertEquals("example.com", vm.uiState.value.resolvedHost)
        // UseCase normalized before resolver saw it:
        assertEquals("https://example.com/x", resolver.received)
        assertFalse(vm.uiState.value.isResolving)
    }

    @Test
    fun `resolve with stub resolver shows honest not-available message`() = runTest {
        val vm = viewModel()
        vm.onUrlChange("https://example.com/x")
        vm.onResolve()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isResolving)
        assertNull(vm.uiState.value.resolvedHost)
        assertTrue(vm.uiState.value.errorMessage!!.contains("isn't available yet"))
    }

    @Test
    fun `invalid url resolve surfaces validation message and never reaches resolver`() = runTest {
        val vm = viewModel()
        vm.onUrlChange("not a url")
        vm.onResolve()
        advanceUntilIdle()

        assertNull(resolver.received)
        assertEquals("That doesn't look like a valid link", vm.uiState.value.validationMessage)
    }

    @Test
    fun `download queues via use case`() = runTest {
        val vm = viewModel()
        vm.onUrlChange("  https://example.com/file.mp4  ")
        vm.onDownload()
        advanceUntilIdle()

        assertEquals(listOf("https://example.com/file.mp4"), downloadRepo.enqueued)
        assertTrue(vm.uiState.value.queuedSuccessfully)
        assertFalse(vm.uiState.value.isDownloading)
    }

    @Test
    fun `invalid url download never reaches repository`() = runTest {
        val vm = viewModel()
        vm.onUrlChange("javascript:alert(1)")
        vm.onDownload()
        advanceUntilIdle()

        assertEquals(0, downloadRepo.enqueued.size)
        assertFalse(vm.uiState.value.queuedSuccessfully)
    }

    @Test
    fun `repository failure shows error without crash`() = runTest {
        downloadRepo.fail = true
        val vm = viewModel()
        vm.onUrlChange("https://example.com/f.mp4")
        vm.onDownload()
        advanceUntilIdle()

        assertEquals("Couldn't queue the download.", vm.uiState.value.errorMessage)
        assertFalse(vm.uiState.value.queuedSuccessfully)
    }

    @Test
    fun `queued flag resets after consumption`() = runTest {
        val vm = viewModel()
        vm.onUrlChange("https://example.com/f.mp4")
        vm.onDownload()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.queuedSuccessfully)

        vm.onQueuedMessageShown()
        assertFalse(vm.uiState.value.queuedSuccessfully)
    }

    @Test
    fun `clear resets to initial`() = runTest {
        val vm = viewModel()
        vm.onUrlChange("https://example.com/a")
        vm.onClear()
        assertEquals(HomeUiState(), vm.uiState.value)
    }

    @Test
    fun `double resolve is ignored while resolving`() = runTest {
        val vm = viewModel()
        vm.onUrlChange("https://example.com/a")
        vm.onResolve()
        vm.onResolve() // no-op while busy
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isResolving)
    }
}
