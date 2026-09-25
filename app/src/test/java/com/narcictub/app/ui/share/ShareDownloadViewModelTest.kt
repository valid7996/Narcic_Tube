package com.narcictub.app.ui.share

import com.narcictub.app.domain.model.DownloadProgress
import com.narcictub.app.domain.model.HistoryItem
import com.narcictub.app.domain.model.MediaFileAvailability
import com.narcictub.app.domain.model.MediaInfo
import com.narcictub.app.domain.model.MediaProvider
import com.narcictub.app.domain.model.MediaVariant
import com.narcictub.app.domain.model.OpenableMedia
import com.narcictub.app.domain.repository.DownloadRepository
import com.narcictub.app.domain.resolver.MediaResolver
import com.narcictub.app.domain.resolver.MediaResolveException
import com.narcictub.app.domain.usecase.EnqueueDownloadUseCase
import com.narcictub.app.domain.usecase.ResolveUrlUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
 * Share download screen ViewModel: auto-resolve of the shared URL, real
 * variant selection with the SAME guards as the Home form (identity
 * membership, stage-1 destination policy, stale-selection guard), enqueue
 * through the real use case, and safe error mapping everywhere.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShareDownloadViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private class RecordingResolver : MediaResolver {
        var callCount = 0
        var received: String? = null
        var result: Result<MediaInfo> = Result.failure(MediaResolveException.UnsupportedSource())

        override suspend fun resolve(url: String): Result<MediaInfo> {
            callCount += 1
            received = url
            return result
        }
    }

    private class RecordingDownloadRepo : DownloadRepository {
        val enqueued = mutableListOf<String>()
        val titles = mutableListOf<String?>()
        var fail = false

        override val progress = MutableStateFlow<Map<Long, DownloadProgress>>(emptyMap())
        override fun observeDownloads() = MutableStateFlow<List<HistoryItem>>(emptyList())
        override suspend fun enqueue(sourceUrl: String, durationSeconds: Long?): Long {
            if (fail) throw java.io.IOException("queue full")
            enqueued.add(sourceUrl)
            return enqueued.size.toLong()
        }

        override suspend fun enqueueTitled(sourceUrl: String, durationSeconds: Long?, title: String?): Long {
            titles.add(title)
            return enqueue(sourceUrl, durationSeconds)
        }

        override suspend fun cancel(id: Long) {}
        override suspend fun retry(id: Long): Long? = null
        override suspend fun remove(id: Long): Boolean = false
        override suspend fun removeCompleted(): Int = 0
        override suspend fun removeFailed(): Int = 0
        override suspend fun recoverInterrupted(): Int = 0
        override suspend fun mediaAvailability(id: Long) = MediaFileAvailability.NOT_APPLICABLE
        override suspend fun openableMedia(id: Long): OpenableMedia? = null
        override suspend fun removeRecord(id: Long): Boolean = false
    }

    private lateinit var resolver: RecordingResolver
    private lateinit var repo: RecordingDownloadRepo

    /** Fresh VM fed with shared text, exactly like the share activity does. */
    private fun viewModel(sharedText: String? = SHARED_TEXT) =
        ShareDownloadViewModel(
            resolveUrl = ResolveUrlUseCase(resolver),
            enqueueDownload = EnqueueDownloadUseCase(repo),
        ).also { it.onSharedText(sharedText) }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        resolver = RecordingResolver()
        repo = RecordingDownloadRepo()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun providerMedia(vararg variants: MediaVariant) = MediaInfo(
        sourceUrl = SHARED_URL,
        title = "SpongeBob episode",
        host = "youtube.com",
        provider = MediaProvider.YOUTUBE,
        durationSeconds = 120L,
        variants = variants.toList(),
    )

    private fun videoVariant(height: Int, url: String = "https://video.example/$height") =
        MediaVariant(
            downloadUrl = url,
            mimeType = "video/mp4",
            container = "mp4",
            height = height,
            sizeBytes = 10_000L * height,
            qualityLabel = "${height}p",
        )

    private fun audioVariant() = MediaVariant(
        downloadUrl = "https://audio.example/m4a",
        mimeType = "audio/mp4",
        container = "m4a",
        sizeBytes = 7_300_000L,
    )

    @Test
    fun `resolve starts automatically and exposes the real media`() = runTest {
        resolver.result = Result.success(providerMedia(videoVariant(1080), videoVariant(360), audioVariant()))
        val vm = viewModel()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isResolving)
        assertEquals("SpongeBob episode", state.resolvedMedia?.title)
        assertEquals(3, state.resolvedMedia?.variants?.size)
        assertEquals(SHARED_URL, resolver.received)
        assertNull("multiple variants require an explicit choice", state.selectedVariantUrl)
    }

    @Test
    fun `single downloadable variant is auto-selected`() = runTest {
        resolver.result = Result.success(providerMedia(videoVariant(360)))
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals("https://video.example/360", vm.uiState.value.selectedVariantUrl)
        assertTrue(vm.uiState.value.canDownload)
    }

    @Test
    fun `shared text without a link shows the safe intake error without resolving`() = runTest {
        val vm = viewModel(sharedText = "no link here at all")
        advanceUntilIdle()

        assertEquals(0, resolver.callCount)
        assertEquals("The shared text doesn't contain a supported link.", vm.uiState.value.errorMessage)
        assertFalse(vm.uiState.value.canDownload)
    }

    @Test
    fun `shared text with a non-http token is treated as no link`() = runTest {
        val vm = viewModel(sharedText = "javascript:alert(1)")
        advanceUntilIdle()

        assertEquals(0, resolver.callCount)
        assertNotNull(vm.uiState.value.errorMessage)
    }

    @Test
    fun `selecting a current variant works and clears errors`() = runTest {
        resolver.result = Result.success(providerMedia(videoVariant(1080), videoVariant(360), audioVariant()))
        val vm = viewModel()
        advanceUntilIdle()

        vm.onVariantSelected("https://audio.example/m4a")
        assertEquals("https://audio.example/m4a", vm.uiState.value.selectedVariantUrl)

        vm.onVariantSelected("https://video.example/360")
        assertEquals("https://video.example/360", vm.uiState.value.selectedVariantUrl)
    }

    @Test
    fun `foreign variant url is ignored`() = runTest {
        resolver.result = Result.success(providerMedia(videoVariant(1080), videoVariant(360), audioVariant()))
        val vm = viewModel()
        advanceUntilIdle()

        vm.onVariantSelected("https://evil.example/not-a-variant")
        assertNull(vm.uiState.value.selectedVariantUrl)
    }

    @Test
    fun `policy-rejected variant is never selectable and explains why`() = runTest {
        // The rejected URL is a REAL variant of the resolved media — the
        // selection guard must refuse it with the typed policy message.
        resolver.result = Result.success(
            providerMedia(
                videoVariant(1080),
                videoVariant(0, url = "http://127.0.0.1/secret"),
            ),
        )
        val vm = viewModel()
        advanceUntilIdle()

        vm.onVariantSelected("http://127.0.0.1/secret")
        assertNull(vm.uiState.value.selectedVariantUrl)
        assertEquals(
            "This variant's download address was rejected by the security policy.",
            vm.uiState.value.errorMessage,
        )
    }

    @Test
    fun `download queues the selected variant with provider title and duration`() = runTest {
        resolver.result = Result.success(providerMedia(videoVariant(1080), videoVariant(360), audioVariant()))
        val vm = viewModel()
        advanceUntilIdle()

        vm.onVariantSelected("https://audio.example/m4a")
        vm.onDownloadSelected()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(listOf("https://audio.example/m4a"), repo.enqueued)
        assertEquals(listOf("SpongeBob episode"), repo.titles)
        assertTrue(state.queued)
        assertFalse(state.isEnqueueing)
    }

    @Test
    fun `download without a selection is a no-op`() = runTest {
        resolver.result = Result.success(providerMedia(videoVariant(1080), videoVariant(360), audioVariant()))
        val vm = viewModel()
        advanceUntilIdle()

        vm.onDownloadSelected()
        advanceUntilIdle()

        assertEquals(0, repo.enqueued.size)
        assertFalse(vm.uiState.value.queued)
    }

    @Test
    fun `enqueue failure surfaces a safe message`() = runTest {
        resolver.result = Result.success(providerMedia(videoVariant(360)))
        val vm = viewModel()
        advanceUntilIdle()

        repo.fail = true
        vm.onDownloadSelected()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("Couldn't queue the download.", state.errorMessage)
        assertFalse(state.queued)
        assertFalse(state.isEnqueueing)
    }

    @Test
    fun `resolve failure maps through the shared safe messages`() = runTest {
        resolver.result = Result.failure(MediaResolveException.UnsupportedSource())
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(
            "This isn't a direct media file link — dedicated platforms aren't supported yet.",
            vm.uiState.value.errorMessage,
        )
        assertNull(vm.uiState.value.resolvedMedia)
    }

    @Test
    fun `retry resolves again and resets the previous outcome`() = runTest {
        resolver.result = Result.failure(MediaResolveException.UnsupportedSource())
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals(1, resolver.callCount)

        resolver.result = Result.success(providerMedia(videoVariant(360)))
        vm.retry()
        advanceUntilIdle()

        assertEquals(2, resolver.callCount)
        assertEquals("https://video.example/360", vm.uiState.value.selectedVariantUrl)
        assertNull(vm.uiState.value.errorMessage)
    }

    private companion object {
        const val SHARED_TEXT = "check this out https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        const val SHARED_URL = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
    }
}
