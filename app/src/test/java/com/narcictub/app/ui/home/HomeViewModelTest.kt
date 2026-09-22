package com.narcictub.app.ui.home

import app.cash.turbine.test
import com.narcictub.app.domain.model.MediaInfo
import com.narcictub.app.domain.resolver.MediaResolveException
import com.narcictub.app.domain.resolver.MediaResolver
import com.narcictub.app.domain.usecase.EnqueueDownloadUseCase
import com.narcictub.app.domain.usecase.ResolveUrlUseCase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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
 * HomeViewModel Phase 8: resolve flows through ResolveUrlUseCase with real
 * MediaInfo on success, safe error mapping on failure, duplicate-request
 * protection, cancellation via onClear, and download enqueue offered only
 * for genuinely resolved direct files.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private class RecordingResolver : MediaResolver {
        var received: String? = null
        var callCount = 0
        var result: Result<MediaInfo> = Result.failure(MediaResolveException.UnsupportedSource())
        override suspend fun resolve(url: String): Result<MediaInfo> {
            callCount += 1
            received = url
            return result
        }
    }

    /** Resolver that never returns until [gate] completes — for cancel tests. */
    private class GatedResolver(private val gate: CompletableDeferred<Result<MediaInfo>>) :
        MediaResolver {
        override suspend fun resolve(url: String): Result<MediaInfo> = gate.await()
    }

    /** PHASE 17: per-call gated resolver to pin share-intake cancellation. */
    private class QueueResolver : MediaResolver {
        private val gates = ArrayDeque<CompletableDeferred<Result<MediaInfo>>>()
        val urls = mutableListOf<String>()

        fun enqueueGate(): CompletableDeferred<Result<MediaInfo>> =
            CompletableDeferred<Result<MediaInfo>>().also { gates.addLast(it) }

        override suspend fun resolve(url: String): Result<MediaInfo> {
            urls.add(url)
            val gate = gates.removeFirstOrNull() ?: CompletableDeferred(
                Result.success(
                    MediaInfo(
                        sourceUrl = url,
                        title = url.substringAfterLast('/'),
                        host = "example.com",
                        isDirectFile = true,
                    ),
                ),
            )
            return gate.await()
        }
    }

    private class RecordingDownloadRepo : com.narcictub.app.domain.repository.DownloadRepository {
        val enqueued = mutableListOf<String>()
        var fail = false
        private var enqueueGate: CompletableDeferred<Unit>? = null

        /** Phase 20 Case B: holds enqueue calls until released. */
        fun gateEnqueue(): CompletableDeferred<Unit> =
            CompletableDeferred<Unit>().also { enqueueGate = it }

        override val progress = kotlinx.coroutines.flow.MutableStateFlow<Map<Long, com.narcictub.app.domain.model.DownloadProgress>>(emptyMap())
        override fun observeDownloads() = kotlinx.coroutines.flow.MutableStateFlow<List<com.narcictub.app.domain.model.HistoryItem>>(emptyList())
        override suspend fun enqueue(sourceUrl: String, durationSeconds: Long?): Long {
            if (fail) throw java.io.IOException("queue full")
            enqueueGate?.await()
            enqueued.add(sourceUrl)
            return enqueued.size.toLong()
        }
        /** Titles passed through enqueueTitled (provider media). */
        val titles = mutableListOf<String?>()
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
        override suspend fun mediaAvailability(id: Long) =
            com.narcictub.app.domain.model.MediaFileAvailability.NOT_APPLICABLE
        override suspend fun openableMedia(id: Long) = null
        override suspend fun removeRecord(id: Long): Boolean = false
    }

    private lateinit var resolver: RecordingResolver
    private lateinit var downloadRepo: RecordingDownloadRepo

    private fun viewModel() = HomeViewModel(
        resolveUrl = ResolveUrlUseCase(resolver),
        enqueueDownload = EnqueueDownloadUseCase(downloadRepo),
    )

    private fun directFileInfo(url: String = "https://example.com/x") = MediaInfo(
        sourceUrl = url,
        title = "clip.mp4",
        host = "example.com",
        mimeType = "video/mp4",
        sizeBytes = 2048L,
        isDirectFile = true,
        downloadUrl = url,
        // Phase 20: a resolved direct file carries its real variant.
        variants = listOf(
            com.narcictub.app.domain.model.MediaVariant(
                downloadUrl = url,
                mimeType = "video/mp4",
                container = "mp4",
                sizeBytes = 2048L,
            ),
        ),
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
        assertNull(state.resolvedMedia)
        assertFalse(state.canDownload)
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
    fun `resolve shows loading then real metadata on success`() = runTest {
        resolver.result = Result.success(directFileInfo())
        val vm = viewModel()
        vm.onUrlChange("https://example.com/x")

        vm.onResolve()
        assertTrue("loading state set synchronously", vm.uiState.value.isResolving)

        advanceUntilIdle()

        assertFalse(vm.uiState.value.isResolving)
        val info = vm.uiState.value.resolvedMedia
        assertEquals("clip.mp4", info?.title)
        assertEquals("example.com", info?.host)
        assertEquals("video/mp4", info?.mimeType)
        assertEquals(2048L, info?.sizeBytes)
        assertTrue(info?.isDirectFile == true)
        assertNull(vm.uiState.value.errorMessage)
        // UseCase normalized before resolver saw it:
        assertEquals("https://example.com/x", resolver.received)
    }

    @Test
    fun `unsupported source shows honest message without metadata`() = runTest {
        val vm = viewModel()
        vm.onUrlChange("https://youtube.com/watch?v=1")
        vm.onResolve()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isResolving)
        assertNull(vm.uiState.value.resolvedMedia)
        assertFalse(vm.uiState.value.canDownload)
        assertTrue(vm.uiState.value.errorMessage!!.contains("supported"))
    }

    @Test
    fun `http failure maps to a safe message with status code`() = runTest {
        resolver.result = Result.failure(MediaResolveException.Http(404))
        val vm = viewModel()
        vm.onUrlChange("https://example.com/gone.mp4")
        vm.onResolve()
        advanceUntilIdle()

        assertNull(vm.uiState.value.resolvedMedia)
        assertEquals("The source server answered with an error (HTTP 404).", vm.uiState.value.errorMessage)
    }

    @Test
    fun `network failure maps to a safe connection message`() = runTest {
        resolver.result = Result.failure(
            MediaResolveException.Network(java.io.IOException("connection refused")),
        )
        val vm = viewModel()
        vm.onUrlChange("https://unreachable.example.com/f.mp4")
        vm.onResolve()
        advanceUntilIdle()

        assertEquals("Couldn't reach the source server. Check your connection and try again.", vm.uiState.value.errorMessage)
        // Raw exception text is never exposed.
        assertFalse(vm.uiState.value.errorMessage!!.contains("connection refused"))
    }

    @Test
    fun `policy failure maps to a safe blocked message`() = runTest {
        resolver.result = Result.failure(MediaResolveException.Policy("private or local addresses are not allowed"))
        val vm = viewModel()
        vm.onUrlChange("http://127.0.0.1/x")
        vm.onResolve()
        advanceUntilIdle()

        assertEquals("This link points to a blocked destination and can't be used.", vm.uiState.value.errorMessage)
        assertNull(vm.uiState.value.resolvedMedia)
    }

    @Test
    fun `unknown failure maps to the generic message`() = runTest {
        resolver.result = Result.failure(IllegalStateException("internal detail that must not leak"))
        val vm = viewModel()
        vm.onUrlChange("https://example.com/x")
        vm.onResolve()
        advanceUntilIdle()

        assertEquals("Couldn't resolve that link.", vm.uiState.value.errorMessage)
        assertFalse(vm.uiState.value.errorMessage!!.contains("internal detail"))
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

    // ===== smart auto-resolve (no manual Resolve tap needed) =====

    @Test
    fun `a valid url resolves itself after the user stops typing`() = runTest {
        resolver.result = Result.success(directFileInfo("https://example.com/clip.mp4"))
        val vm = viewModel()

        vm.onUrlChange("https://example.com/clip.mp4")
        assertEquals("nothing happens before the debounce settles", 0, resolver.callCount)

        advanceUntilIdle()

        assertEquals(1, resolver.callCount)
        assertEquals("https://example.com/clip.mp4", resolver.received)
        assertTrue(vm.uiState.value.canDownload)
    }

    @Test
    fun `further typing restarts the debounce instead of resolving every keystroke`() = runTest {
        resolver.result = Result.success(directFileInfo("https://example.com/final.mp4"))
        val vm = viewModel()

        vm.onUrlChange("https://example.com/f")
        advanceTimeBy(300)
        vm.onUrlChange("https://example.com/fi")
        advanceTimeBy(300)
        vm.onUrlChange("https://example.com/final.mp4")
        advanceUntilIdle()

        assertEquals(1, resolver.callCount)
        assertEquals("https://example.com/final.mp4", resolver.received)
    }

    @Test
    fun `a manual resolve that finishes first is not duplicated by the debounce`() = runTest {
        resolver.result = Result.success(directFileInfo("https://example.com/a.mp4"))
        val vm = viewModel()

        vm.onUrlChange("https://example.com/a.mp4")
        vm.onResolve()
        advanceUntilIdle()

        assertEquals(1, resolver.callCount)
    }

    @Test
    fun `clearing the form cancels a pending auto-resolve`() = runTest {
        val vm = viewModel()
        vm.onUrlChange("https://example.com/clip.mp4")
        vm.onClear()
        advanceUntilIdle()

        assertEquals(0, resolver.callCount)
    }

    // ===== smart clipboard suggestion =====

    @Test
    fun `a new supported link on the clipboard is offered as a suggestion`() = runTest {
        val vm = viewModel()
        vm.onClipboardTextObserved("https://youtu.be/abc123")

        assertEquals("https://youtu.be/abc123", vm.uiState.value.clipboardSuggestion)
    }

    @Test
    fun `unsupported clipboard text is not suggested`() = runTest {
        val vm = viewModel()
        vm.onClipboardTextObserved("just some notes, not a link")

        assertNull(vm.uiState.value.clipboardSuggestion)
    }

    @Test
    fun `the same clip is never suggested twice`() = runTest {
        val vm = viewModel()
        vm.onClipboardTextObserved("https://youtu.be/abc123")
        vm.onClipboardSuggestionDismissed()
        vm.onClipboardTextObserved("https://youtu.be/abc123")

        assertNull(vm.uiState.value.clipboardSuggestion)
    }

    @Test
    fun `a clip matching the url already in the field is not suggested`() = runTest {
        val vm = viewModel()
        vm.onUrlChange("https://youtu.be/abc123")
        vm.onClipboardTextObserved("https://youtu.be/abc123")

        assertNull(vm.uiState.value.clipboardSuggestion)
    }

    @Test
    fun `accepting the clipboard suggestion fills the field and resolves immediately`() = runTest {
        resolver.result = Result.success(directFileInfo("https://example.com/clip.mp4"))
        val vm = viewModel()
        vm.onClipboardTextObserved("https://example.com/clip.mp4")

        vm.onClipboardSuggestionAccepted()
        advanceUntilIdle()

        assertEquals("https://example.com/clip.mp4", vm.uiState.value.url)
        assertNull(vm.uiState.value.clipboardSuggestion)
        assertTrue(vm.uiState.value.canDownload)
    }

    @Test
    fun `dismissing the clipboard suggestion only clears the suggestion`() = runTest {
        val vm = viewModel()
        vm.onClipboardTextObserved("https://youtu.be/abc123")

        vm.onClipboardSuggestionDismissed()

        assertNull(vm.uiState.value.clipboardSuggestion)
        assertEquals("", vm.uiState.value.url)
        assertEquals(0, resolver.callCount)
    }

    @Test
    fun `duplicate resolve while busy issues exactly one resolver call`() = runTest {
        val vm = viewModel()
        vm.onUrlChange("https://example.com/a")
        vm.onResolve()
        vm.onResolve()
        vm.onResolve()
        advanceUntilIdle()

        assertEquals(1, resolver.callCount)
        assertFalse(vm.uiState.value.isResolving)
    }

    @Test
    fun `resolve can be issued again after completing`() = runTest {
        val vm = viewModel()
        vm.onUrlChange("https://example.com/a")
        vm.onResolve()
        advanceUntilIdle()

        vm.onResolve()
        advanceUntilIdle()

        assertEquals(2, resolver.callCount)
    }

    @Test
    fun `clear cancels an in-flight resolve and stale results never land`() = runTest {
        val gate = CompletableDeferred<Result<MediaInfo>>()
        val gatedResolver = GatedResolver(gate)
        val vm = HomeViewModel(
            resolveUrl = ResolveUrlUseCase(gatedResolver),
            enqueueDownload = EnqueueDownloadUseCase(downloadRepo),
        )
        vm.onUrlChange("https://example.com/slow")
        vm.onResolve()
        testScheduler.runCurrent() // let the launch reach gate.await()

        vm.onClear()
        assertEquals(HomeUiState(), vm.uiState.value)

        // The resolve completes AFTER clear — a stale success must be dropped.
        gate.complete(Result.success(directFileInfo("https://example.com/slow")))
        advanceUntilIdle()

        assertNull(vm.uiState.value.resolvedMedia)
        assertFalse(vm.uiState.value.isResolving)
    }

    @Test
    fun `new url input clears stale resolved media`() = runTest {
        resolver.result = Result.success(directFileInfo())
        val vm = viewModel()
        vm.onUrlChange("https://example.com/x")
        vm.onResolve()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.canDownload)

        vm.onUrlChange("https://example.com/other")
        assertNull(vm.uiState.value.resolvedMedia)
        assertFalse(vm.uiState.value.canDownload)
    }

    @Test
    fun `download queues via use case after genuine resolution`() = runTest {
        resolver.result = Result.success(directFileInfo("https://example.com/file.mp4"))
        val vm = viewModel()
        vm.onUrlChange("  https://example.com/file.mp4  ")
        vm.onResolve()
        advanceUntilIdle()
        vm.onDownload()
        advanceUntilIdle()

        assertEquals(listOf("https://example.com/file.mp4"), downloadRepo.enqueued)
        assertTrue(vm.uiState.value.queuedSuccessfully)
        assertFalse(vm.uiState.value.isDownloading)
    }

    @Test
    fun `download is refused before a genuine resolution`() = runTest {
        val vm = viewModel()
        vm.onUrlChange("https://example.com/f.mp4")
        // No resolve performed — enqueue must not run.
        vm.onDownload()
        advanceUntilIdle()

        assertEquals(0, downloadRepo.enqueued.size)
        assertFalse(vm.uiState.value.queuedSuccessfully)
    }

    @Test
    fun `download is refused after unsupported resolution`() = runTest {
        val vm = viewModel()
        vm.onUrlChange("https://youtube.com/watch?v=1")
        vm.onResolve()
        advanceUntilIdle()
        vm.onDownload()
        advanceUntilIdle()

        assertEquals(0, downloadRepo.enqueued.size)
    }

    @Test
    fun `download is refused for non-direct media info`() = runTest {
        // Phase 20 semantics: a resolved media WITHOUT real variants offers
        // no download — nothing is selected, nothing is invented.
        resolver.result = Result.success(
            directFileInfo().copy(isDirectFile = false, variants = emptyList()),
        )
        val vm = viewModel()
        vm.onUrlChange("https://example.com/x")
        vm.onResolve()
        advanceUntilIdle()
        vm.onDownload()
        advanceUntilIdle()

        assertEquals(0, downloadRepo.enqueued.size)
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
        resolver.result = Result.success(directFileInfo("https://example.com/f.mp4"))
        val vm = viewModel()
        vm.onUrlChange("https://example.com/f.mp4")
        vm.onResolve()
        advanceUntilIdle()
        vm.onDownload()
        advanceUntilIdle()

        assertEquals("Couldn't queue the download.", vm.uiState.value.errorMessage)
        assertFalse(vm.uiState.value.queuedSuccessfully)
    }

    @Test
    fun `queued flag resets after consumption`() = runTest {
        resolver.result = Result.success(directFileInfo("https://example.com/f.mp4"))
        val vm = viewModel()
        vm.onUrlChange("https://example.com/f.mp4")
        vm.onResolve()
        advanceUntilIdle()
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

    // ===== Phase 17: Android Share intake =====

    @Test
    fun `unsupported provider maps to a provider-aware safe message`() = runTest {
        resolver.result = Result.failure(
            MediaResolveException.UnsupportedProvider(com.narcictub.app.domain.model.MediaProvider.YOUTUBE),
        )
        val vm = viewModel()
        vm.onUrlChange("https://youtube.com/watch?v=1")
        vm.onResolve()
        advanceUntilIdle()

        assertNull(vm.uiState.value.resolvedMedia)
        assertFalse(vm.uiState.value.canDownload)
        val message = vm.uiState.value.errorMessage
        assertEquals(
            "YouTube links aren't supported yet — extraction for this provider hasn't been implemented.",
            message,
        )
    }

    @Test
    fun `instagram extraction-unavailable maps to the honest no-legitimate-path message`() = runTest {
        // Phase 19: the real Instagram extractor fails with
        // ExtractionUnavailable — the UI must say "no legitimate path",
        // never "Download failed".
        resolver.result = Result.failure(
            MediaResolveException.ExtractionUnavailable(com.narcictub.app.domain.model.MediaProvider.INSTAGRAM),
        )
        val vm = viewModel()
        vm.onUrlChange("https://www.instagram.com/p/Cabc123/")
        vm.onResolve()
        advanceUntilIdle()

        assertNull(vm.uiState.value.resolvedMedia)
        assertFalse(vm.uiState.value.canDownload)
        assertEquals(
            "Content from Instagram can't be retrieved yet — " +
                "there's no legitimate access path available to this app.",
            vm.uiState.value.errorMessage,
        )
    }

    @Test
    fun `yt-dlp login-required failure maps to a cookies hint`() = runTest {
        resolver.result = Result.failure(
            MediaResolveException.ExtractionFailed(
                com.narcictub.app.domain.model.MediaProvider.INSTAGRAM,
                MediaResolveException.ExtractionFailed.Reason.LOGIN_REQUIRED,
            ),
        )
        val vm = viewModel()
        vm.onUrlChange("https://www.instagram.com/p/Cabc123/")
        vm.onResolve()
        advanceUntilIdle()

        assertNull(vm.uiState.value.resolvedMedia)
        assertFalse(vm.uiState.value.canDownload)
        assertEquals(
            "Instagram asked for a login to show this link. " +
                "Import your browser's cookies.txt in Settings and try again.",
            vm.uiState.value.errorMessage,
        )
    }

    @Test
    fun `provider media is enqueued with its real title`() = runTest {
        val pageUrl = "https://www.youtube.com/watch?v=abc123"
        val variantUrl = "$pageUrl#nt-f=18"
        resolver.result = Result.success(
            MediaInfo(
                sourceUrl = pageUrl,
                title = "Sample video",
                host = "www.youtube.com",
                provider = com.narcictub.app.domain.model.MediaProvider.YOUTUBE,
                variants = listOf(
                    com.narcictub.app.domain.model.MediaVariant(
                        downloadUrl = variantUrl,
                        container = "mp4",
                        height = 360,
                        qualityLabel = "360p",
                    ),
                ),
            ),
        )
        val vm = viewModel()
        vm.onUrlChange(pageUrl)
        vm.onResolve()
        advanceUntilIdle()
        assertTrue("the single variant is auto-selected", vm.uiState.value.canDownload)

        vm.onDownload()
        advanceUntilIdle()

        assertEquals(listOf(variantUrl), downloadRepo.enqueued)
        assertEquals(listOf<String?>("Sample video"), downloadRepo.titles)
    }

    @Test
    fun `direct file media is still enqueued without a title`() = runTest {
        resolver.result = Result.success(directFileInfo("https://example.com/clip.mp4"))
        val vm = viewModel()
        vm.onUrlChange("https://example.com/clip.mp4")
        vm.onResolve()
        advanceUntilIdle()

        vm.onDownload()
        advanceUntilIdle()

        assertEquals(listOf("https://example.com/clip.mp4"), downloadRepo.enqueued)
        assertTrue("direct links keep the plain enqueue path", downloadRepo.titles.isEmpty())
    }

    @Test
    fun `shared url cancels an in-flight resolve and resolves the new url`() = runTest {
        val gated = QueueResolver()
        val gate1 = gated.enqueueGate()
        val vm = HomeViewModel(
            resolveUrl = ResolveUrlUseCase(gated),
            enqueueDownload = EnqueueDownloadUseCase(downloadRepo),
        )
        vm.onUrlChange("https://example.com/first.mp4")
        vm.onResolve()
        testScheduler.runCurrent() // first resolve is now in flight
        assertEquals(listOf("https://example.com/first.mp4"), gated.urls)

        // The share arrives while the first resolve is still running.
        vm.onSharedUrlReceived("https://example.com/second.mp4")
        advanceUntilIdle()

        // First resolve was cancelled before completing; the shared URL
        // resolved exactly once and its real metadata landed.
        assertEquals(
            listOf("https://example.com/first.mp4", "https://example.com/second.mp4"),
            gated.urls,
        )
        assertFalse(vm.uiState.value.isResolving)
        assertNull(vm.uiState.value.errorMessage)
        assertEquals("second.mp4", vm.uiState.value.resolvedMedia?.title)
        assertEquals("https://example.com/second.mp4", vm.uiState.value.url)
        assertTrue(gate1.isActive || gate1.isCompleted) // no crash either way
    }

    @Test
    fun `share rejection surfaces a safe message without touching the form`() = runTest {
        val vm = viewModel()
        vm.onUrlChange("https://example.com/keep.mp4")
        vm.onShareRejected("The shared text doesn't contain a supported link.")

        assertEquals("The shared text doesn't contain a supported link.", vm.uiState.value.errorMessage)
        assertEquals("https://example.com/keep.mp4", vm.uiState.value.url)
    }

    // ===== Phase 20: variant selection + download integration =====

    private fun variant(
        url: String,
        height: Int? = null,
        sizeBytes: Long? = null,
        quality: String? = null,
    ) = com.narcictub.app.domain.model.MediaVariant(
        downloadUrl = url,
        mimeType = "video/mp4",
        container = "mp4",
        width = height?.let { (it * 16) / 9 },
        height = height,
        sizeBytes = sizeBytes,
        qualityLabel = quality,
    )

    @Test
    fun `single variant is auto-selected after resolve`() = runTest {
        resolver.result = Result.success(directFileInfo("https://example.com/x"))
        val vm = viewModel()
        vm.onUrlChange("https://example.com/x")
        vm.onResolve()
        advanceUntilIdle()

        assertEquals("https://example.com/x", vm.uiState.value.selectedVariantUrl)
        assertTrue(vm.uiState.value.canDownload)
    }

    @Test
    fun `multiple variants require an explicit selection`() = runTest {
        resolver.result = Result.success(
            directFileInfo().copy(
                variants = listOf(
                    variant("https://example.com/720.mp4", height = 720),
                    variant("https://example.com/1080.mp4", height = 1080),
                ),
            ),
        )
        val vm = viewModel()
        vm.onUrlChange("https://example.com/x")
        vm.onResolve()
        advanceUntilIdle()

        assertNull("no silent pick among many", vm.uiState.value.selectedVariantUrl)
        assertFalse(vm.uiState.value.canDownload)
        vm.onDownload()
        advanceUntilIdle()
        assertEquals("nothing enqueued without an explicit selection", 0, downloadRepo.enqueued.size)
    }

    @Test
    fun `selecting a variant enables download and updates the selection`() = runTest {
        resolver.result = Result.success(
            directFileInfo().copy(
                variants = listOf(
                    variant("https://example.com/720.mp4", height = 720),
                    variant("https://example.com/1080.mp4", height = 1080),
                ),
            ),
        )
        val vm = viewModel()
        vm.onUrlChange("https://example.com/x")
        vm.onResolve()
        advanceUntilIdle()

        vm.onVariantSelected("https://example.com/1080.mp4")
        assertEquals("https://example.com/1080.mp4", vm.uiState.value.selectedVariantUrl)
        assertTrue(vm.uiState.value.canDownload)

        vm.onVariantSelected("https://example.com/720.mp4")
        assertEquals("https://example.com/720.mp4", vm.uiState.value.selectedVariantUrl)
    }

    @Test
    fun `selection outside the current media is ignored`() = runTest {
        resolver.result = Result.success(directFileInfo("https://example.com/x"))
        val vm = viewModel()
        vm.onUrlChange("https://example.com/x")
        vm.onResolve()
        advanceUntilIdle()

        vm.onVariantSelected("https://evil.example.com/other.mp4")
        assertEquals(
            "foreign identity must never become the selection",
            "https://example.com/x",
            vm.uiState.value.selectedVariantUrl,
        )
    }

    @Test
    fun `unsafe variant url cannot be selected and reports the policy`() = runTest {
        resolver.result = Result.success(
            directFileInfo().copy(
                variants = listOf(
                    variant("https://example.com/safe.mp4"),
                    variant("http://127.0.0.1/secret.mp4"), // hostile resolver output
                ),
            ),
        )
        val vm = viewModel()
        vm.onUrlChange("https://example.com/x")
        vm.onResolve()
        advanceUntilIdle()

        vm.onVariantSelected("http://127.0.0.1/secret.mp4")
        assertNull("policy-blocked identity must never be selected", vm.uiState.value.selectedVariantUrl)
        assertEquals(
            "This variant's download address was rejected by the security policy.",
            vm.uiState.value.errorMessage,
        )
    }

    @Test
    fun `single unsafe variant reports the policy and disables download`() = runTest {
        // Untrusted resolver returned a private-address variant as the only
        // rendition — never auto-selected, never enqueued, honest message.
        resolver.result = Result.success(
            directFileInfo().copy(
                downloadUrl = "http://127.0.0.1/secret.mp4",
                variants = listOf(variant("http://127.0.0.1/secret.mp4")),
            ),
        )
        val vm = viewModel()
        vm.onUrlChange("https://example.com/x")
        vm.onResolve()
        advanceUntilIdle()

        assertNull(vm.uiState.value.selectedVariantUrl)
        assertFalse(vm.uiState.value.canDownload)
        assertEquals(
            "This media's download address was rejected by the security policy.",
            vm.uiState.value.errorMessage,
        )
        vm.onDownload()
        advanceUntilIdle()
        assertEquals(0, downloadRepo.enqueued.size)
    }

    @Test
    fun `new resolve invalidates the previous selection and enqueues only the new media`() = runTest {
        // Case A end-to-end: resolve A → select A1 → resolve B → download
        // must enqueue B's variant and never A1's.
        val mediaA = directFileInfo("https://example.com/a-source").copy(
            variants = listOf(
                variant("https://example.com/a-720.mp4", height = 720),
                variant("https://example.com/a-1080.mp4", height = 1080),
            ),
        )
        val mediaB = directFileInfo("https://example.com/b-source")
        resolver.result = Result.success(mediaA)
        val vm = viewModel()
        vm.onUrlChange("https://example.com/a-source")
        vm.onResolve()
        advanceUntilIdle()
        vm.onVariantSelected("https://example.com/a-1080.mp4")
        assertEquals("https://example.com/a-1080.mp4", vm.uiState.value.selectedVariantUrl)

        resolver.result = Result.success(mediaB)
        vm.onSharedUrlReceived("https://example.com/b-source")
        advanceUntilIdle()

        assertEquals("selection now belongs to B", "https://example.com/b-source", vm.uiState.value.selectedVariantUrl)
        vm.onDownload()
        advanceUntilIdle()
        assertEquals(
            "stale A1 must never be enqueued",
            listOf("https://example.com/b-source"),
            downloadRepo.enqueued,
        )
    }

    @Test
    fun `download enqueues the selected variant url not the source url`() = runTest {
        resolver.result = Result.success(
            directFileInfo("https://example.com/watch/123").copy(
                variants = listOf(variant("https://cdn.example.com/final.mp4", sizeBytes = 4096)),
            ),
        )
        val vm = viewModel()
        vm.onUrlChange("https://example.com/watch/123")
        vm.onResolve()
        advanceUntilIdle()
        vm.onDownload()
        advanceUntilIdle()

        assertEquals(
            listOf("https://cdn.example.com/final.mp4"),
            downloadRepo.enqueued,
        )
        assertTrue(vm.uiState.value.queuedSuccessfully)
    }

    @Test
    fun `double download while in flight issues exactly one enqueue`() = runTest {
        resolver.result = Result.success(directFileInfo("https://example.com/x"))
        val vm = viewModel()
        vm.onUrlChange("https://example.com/x")
        vm.onResolve()
        advanceUntilIdle()

        val gate = downloadRepo.gateEnqueue()
        vm.onDownload()
        testScheduler.runCurrent() // first download now waiting at the gate
        vm.onDownload() // duplicate submit — must be suppressed
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("https://example.com/x"), downloadRepo.enqueued)
        assertTrue(vm.uiState.value.queuedSuccessfully)
        assertFalse(vm.uiState.value.isDownloading)
    }

    @Test
    fun `enqueue failure surfaces a safe error and clears the busy state`() = runTest {
        downloadRepo.fail = true
        resolver.result = Result.success(directFileInfo("https://example.com/x"))
        val vm = viewModel()
        vm.onUrlChange("https://example.com/x")
        vm.onResolve()
        advanceUntilIdle()
        vm.onDownload()
        advanceUntilIdle()

        assertEquals("Couldn't queue the download.", vm.uiState.value.errorMessage)
        assertFalse(vm.uiState.value.isDownloading)
        assertFalse(vm.uiState.value.queuedSuccessfully)
        // Recovery: the next attempt is allowed and can succeed.
        downloadRepo.fail = false
        vm.onDownload()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.queuedSuccessfully)
    }

    @Test
    fun `variants are ordered deterministically without discarding any`() {
        // Documented rule: height DESC (nulls last), then size DESC, then URL.
        val a = variant("https://example.com/a.mp4", height = 720, sizeBytes = 10)
        val b = variant("https://example.com/b.mp4", height = 1080, sizeBytes = 10)
        val c = variant("https://example.com/c.mp4", height = 720, sizeBytes = 50)
        val d = variant("https://example.com/d.mp4") // unknown height
        val sorted = sortedVariantsForDisplay(listOf(a, b, c, d))

        assertEquals(
            listOf("https://example.com/b.mp4", "https://example.com/c.mp4", "https://example.com/a.mp4", "https://example.com/d.mp4"),
            sorted.map { it.downloadUrl },
        )
        assertEquals(4, sorted.size) // nothing discarded
    }

    @Test
    fun `turbine observes resolve state transitions`() = runTest {
        resolver.result = Result.success(directFileInfo())
        val vm = viewModel()
        vm.onUrlChange("https://example.com/x")

        vm.uiState.test {
            vm.onResolve() // emits resolving=true synchronously
            skipItems(1) // StateFlow current value at subscription
            assertTrue(awaitItem().isResolving)
            advanceUntilIdle()
            val settled = awaitItem()
            assertFalse(settled.isResolving)
            assertEquals("example.com", settled.resolvedMedia?.host)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
