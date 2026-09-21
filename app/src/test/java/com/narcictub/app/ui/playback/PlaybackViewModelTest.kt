package com.narcictub.app.ui.playback

import androidx.lifecycle.SavedStateHandle
import com.narcictub.app.domain.model.DownloadProgress
import com.narcictub.app.domain.model.HistoryItem
import com.narcictub.app.domain.model.MediaFileAvailability
import com.narcictub.app.domain.model.OpenableMedia
import com.narcictub.app.domain.player.PlaybackEngine
import com.narcictub.app.domain.player.PlaybackErrorReason
import com.narcictub.app.domain.player.PlaybackSource
import com.narcictub.app.domain.repository.DownloadRepository
import com.narcictub.app.domain.usecase.CheckMediaAvailabilityUseCase
import com.narcictub.app.domain.usecase.OpenCompletedMediaUseCase
import java.time.Instant
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * PHASE 11 — PlaybackViewModel tests: re-validation before preparation,
 * safe error/unavailable states, one engine prepare per screen, real
 * duration handling, and the guarantee that playback NEVER mutates
 * download state (no enqueue, no status change, no history deletion).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private class FakeDownloadRepository : DownloadRepository {
        val items = MutableStateFlow<List<HistoryItem>>(emptyList())
        override val progress = MutableStateFlow<Map<Long, DownloadProgress>>(emptyMap())
        override fun observeDownloads() = items

        var availabilityResult = MediaFileAvailability.AVAILABLE
        var openable: OpenableMedia? = null
        val availabilityCalls = mutableListOf<Long>()
        val openCalls = mutableListOf<Long>()

        override suspend fun enqueue(sourceUrl: String, durationSeconds: Long?): Long {
            throw AssertionError("playback must never create a download")
        }
        override suspend fun cancel(id: Long) {
            throw AssertionError("playback must never mutate download state")
        }
        override suspend fun retry(id: Long): Long? {
            throw AssertionError("playback must never mutate download state")
        }
        override suspend fun remove(id: Long): Boolean {
            throw AssertionError("playback must never mutate download state")
        }
        override suspend fun removeCompleted(): Int {
            throw AssertionError("playback must never mutate download state")
        }
        override suspend fun removeFailed(): Int {
            throw AssertionError("playback must never mutate download state")
        }
        override suspend fun recoverInterrupted(): Int {
            throw AssertionError("playback must never mutate download state")
        }
        override suspend fun mediaAvailability(id: Long): MediaFileAvailability {
            availabilityCalls.add(id)
            return availabilityResult
        }
        override suspend fun openableMedia(id: Long): OpenableMedia? {
            openCalls.add(id)
            return openable
        }
        override suspend fun removeRecord(id: Long): Boolean {
            throw AssertionError("playback must never mutate download state")
        }
    }

    private class FakeEngine : PlaybackEngine {
        var prepared: PlaybackSource? = null
        var prepareCalls = 0
        var started = 0
        var paused = 0
        var seeks = mutableListOf<Long>()
        var released = 0
        var surfaces = mutableListOf<Any?>()
        var currentPosition: Long? = null
        var listener: PlaybackEngine.Listener? = null

        /** P22-LOW-1: controls whether play() reports a successful start. */
        var playGranted = true

        override fun prepare(source: PlaybackSource, listener: PlaybackEngine.Listener) {
            prepareCalls++
            prepared = source
            this.listener = listener
        }
        override fun attachSurface(surface: Any?) {
            surfaces.add(surface)
        }
        override fun play(): Boolean {
            if (!playGranted) return false
            started++
            return true
        }
        override fun pause() {
            paused++
        }
        override fun seekTo(positionMs: Long) {
            seeks.add(positionMs)
        }
        override val currentPositionMs: Long?
            get() = currentPosition
        override fun release() {
            released++
        }

        // Test hooks mirroring the real engine's callback path.
        fun emitPrepared(durationMs: Long, hasVideo: Boolean) {
            listener?.onPrepared(durationMs, hasVideo)
        }
        fun emitError(reason: PlaybackErrorReason) {
            listener?.onError(reason)
        }
        fun emitVideoSize(width: Int, height: Int) {
            listener?.onVideoSizeChanged(width, height)
        }
        fun emitTransientFocusLoss() {
            listener?.onPausedByTransientFocusLoss()
        }
        fun emitPermanentFocusLoss() {
            listener?.onPausedByPermanentFocusLoss()
        }
        fun emitFocusGainResume() {
            listener?.onResumedByFocusGain()
        }
    }

    private lateinit var repo: FakeDownloadRepository
    private lateinit var engine: FakeEngine

    private fun viewModel(itemId: Long = 1L): PlaybackViewModel {
        return PlaybackViewModel(
            savedStateHandle = SavedStateHandle(mapOf(PlaybackViewModel.ITEM_ID_KEY to itemId)),
            checkAvailability = CheckMediaAvailabilityUseCase(repo),
            openMedia = OpenCompletedMediaUseCase(repo),
            engine = engine,
        )
    }

    private fun completedRow(id: Long = 1L) = HistoryItem(
        id = id,
        sourceUrl = "https://cdn.example.com/file-$id.mp4?token=secret",
        title = "file-$id.mp4",
        mimeType = "video/mp4",
        status = com.narcictub.app.domain.model.DownloadStatus.COMPLETED,
        sizeBytes = 2048,
        localUri = "content://media/external/downloads/$id",
        createdAt = Instant.ofEpochMilli(id),
        completedAt = Instant.ofEpochMilli(id),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        repo = FakeDownloadRepository()
        engine = FakeEngine()
        repo.items.value = listOf(completedRow())
        repo.openable = OpenableMedia(
            uriText = "content://media/external/downloads/1",
            mimeType = "video/mp4",
            title = "file-1.mp4",
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `available completed record enters playback through validated source`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()

        // Availability checked first, then the validated source resolved.
        assertEquals(listOf(1L), repo.availabilityCalls)
        assertEquals(listOf(1L), repo.openCalls)
        assertEquals("content://media/external/downloads/1", engine.prepared?.uriText)
        assertEquals("video/mp4", engine.prepared?.mimeType)
        assertEquals(1, engine.prepareCalls)

        // Simulate the framework player reporting readiness.
        engine.emitPrepared(65_000, hasVideo = true)
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(PlaybackPhase.READY, state.phase)
        assertEquals("file-1.mp4", state.title)
        assertEquals(65_000L, state.durationMs)
        assertTrue(state.isVideo)
        assertTrue(state.isPlaying)
        assertEquals("player auto-starts the preview", 1, engine.started)
    }

    @Test
    fun `missing media produces the safe unavailable state and never prepares`() = runTest {
        repo.availabilityResult = MediaFileAvailability.UNAVAILABLE
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(PlaybackPhase.UNAVAILABLE, vm.state.value.phase)
        assertEquals(
            "The downloaded file is no longer available on this device.",
            vm.state.value.errorMessage,
        )
        assertEquals("nothing may reach the player", 0, engine.prepareCalls)
        assertEquals(0, repo.openCalls.size)
    }

    @Test
    fun `record that fails re-validation at open time is unavailable`() = runTest {
        repo.openable = null // e.g. file vanished between check and open
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(PlaybackPhase.UNAVAILABLE, vm.state.value.phase)
        assertEquals(0, engine.prepareCalls)
    }

    @Test
    fun `player failure produces a safe error without leaking internals`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        engine.emitError(PlaybackErrorReason.UNSUPPORTED_FORMAT)
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(PlaybackPhase.ERROR, state.phase)
        assertEquals(
            "This file can't be played on this device — the format isn't supported.",
            state.errorMessage,
        )
        assertFalse(state.isPlaying)
    }

    @Test
    fun `player failure does not mutate the download record`() = runTest {
        // The repository fake THROWS on any mutation call — surviving this
        // test proves playback failure never rewrites download state.
        val vm = viewModel()
        advanceUntilIdle()
        engine.emitError(PlaybackErrorReason.GENERIC)
        advanceUntilIdle()

        assertEquals(PlaybackPhase.ERROR, vm.state.value.phase)
        val row = repo.items.value.single()
        assertEquals(com.narcictub.app.domain.model.DownloadStatus.COMPLETED, row.status)
    }

    @Test
    fun `unknown duration stays null instead of a fabricated value`() = runTest {
        repo.openable = OpenableMedia(
            uriText = "content://media/external/downloads/1",
            mimeType = "audio/mpeg",
            title = "track.mp3",
        )
        val vm = viewModel()
        advanceUntilIdle()
        engine.emitPrepared(0, hasVideo = false)
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(PlaybackPhase.READY, state.phase)
        assertNull(state.durationMs)
        assertFalse(state.isVideo) // audio-oriented presentation for unknown
    }

    @Test
    fun `audio mime with no video track gets audio presentation`() = runTest {
        repo.openable = OpenableMedia(
            uriText = "content://media/external/downloads/1",
            mimeType = "audio/mpeg",
            title = "track.mp3",
        )
        val vm = viewModel()
        advanceUntilIdle()
        engine.emitPrepared(120_000, hasVideo = false)
        advanceUntilIdle()

        assertFalse(vm.state.value.isVideo)
        assertEquals(120_000L, vm.state.value.durationMs)
    }

    @Test
    fun `degenerate video dimensions are ignored instead of producing NaN aspect`() = runTest {
        // Phase 11 review cleanup: the player may report 0/negative sizes
        // before real metadata arrives — they must never become Infinity/NaN.
        val vm = viewModel()
        advanceUntilIdle()
        engine.emitPrepared(65_000, hasVideo = true)
        advanceUntilIdle()

        engine.emitVideoSize(0, 0)
        engine.emitVideoSize(1920, 0)
        engine.emitVideoSize(-1, 1080)
        advanceUntilIdle()
        assertNull("degenerate dimensions must not set an aspect ratio", vm.state.value.aspectRatio)

        engine.emitVideoSize(1920, 1080)
        advanceUntilIdle()
        assertEquals(1920f / 1080f, vm.state.value.aspectRatio!!, 0.0001f)
    }

    @Test
    fun `controls delegate to the engine exactly once per action`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        engine.emitPrepared(65_000, hasVideo = true)
        advanceUntilIdle()
        val startedAtReady = engine.started

        vm.onTogglePlay() // playing → pause
        vm.onTogglePlay() // paused → play
        vm.onSeekTo(30_000)
        advanceUntilIdle()

        assertEquals(startedAtReady + 1, engine.started)
        assertEquals(1, engine.paused)
        assertEquals(listOf(30_000L), engine.seeks)
        assertEquals(30_000L, vm.state.value.positionMs)
    }

    @Test
    fun `controls are inert outside the ready phase`() = runTest {
        repo.availabilityResult = MediaFileAvailability.UNAVAILABLE
        val vm = viewModel()
        advanceUntilIdle()

        vm.onTogglePlay()
        vm.onSeekTo(5_000)
        advanceUntilIdle()

        assertEquals(0, engine.started)
        assertEquals(0, engine.seeks.size)
    }

    @Test
    fun `position follows the real player value on each tick`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        engine.emitPrepared(65_000, hasVideo = true)
        advanceUntilIdle()
        assertNull("no position before the first tick", vm.state.value.positionMs.takeIf { it != 0L })

        engine.currentPosition = 12_500
        vm.onTick()
        assertEquals(12_500L, vm.state.value.positionMs)

        // Paused playback does not move the position.
        vm.onTogglePlay() // pause
        engine.currentPosition = 20_000
        vm.onTick()
        assertEquals(12_500L, vm.state.value.positionMs)
    }

    @Test
    fun `surface attach and detach reach the engine`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()

        val fakeSurface = Any() // opaque to the domain layer by design
        vm.onSurfaceAvailable(fakeSurface)
        vm.onSurfaceDestroyed()
        advanceUntilIdle()

        assertEquals(listOf<Any?>(fakeSurface, null), engine.surfaces)
    }

    @Test
    fun `state never contains the uri or source url`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        engine.emitPrepared(65_000, true)
        advanceUntilIdle()

        val rendered = vm.state.value.toString()
        assertFalse(rendered.contains("content://"))
        assertFalse(rendered.contains("token"))
        assertFalse(rendered.contains("https://"))
    }

    @Test
    fun `missing route id resolves to honest unavailable`() = runTest {
        // A record id that does not exist: availability is NOT_APPLICABLE and
        // nothing is ever prepared.
        repo.availabilityResult = MediaFileAvailability.NOT_APPLICABLE
        val vm = PlaybackViewModel(
            savedStateHandle = SavedStateHandle(emptyMap()),
            checkAvailability = CheckMediaAvailabilityUseCase(repo),
            openMedia = OpenCompletedMediaUseCase(repo),
            engine = engine,
        )
        advanceUntilIdle()

        assertEquals(PlaybackPhase.UNAVAILABLE, vm.state.value.phase)
        assertEquals(0, engine.prepareCalls)
    }

    // ===== Phase 22: completion/replay, seek clamping, audio focus =====

    @Test
    fun `completion sets the completed state and replay restarts from zero`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        engine.emitPrepared(65_000, hasVideo = true)
        advanceUntilIdle()

        // End of media:
        engine.listener?.onCompletion()
        advanceUntilIdle()

        assertTrue(vm.state.value.isCompleted)
        assertFalse(vm.state.value.isPlaying)
        assertTrue("no seek yet before replay", engine.seeks.isEmpty())

        // Replay: play while completed restarts from the beginning.
        vm.onTogglePlay()
        assertEquals(listOf(0L), engine.seeks)
        assertFalse(vm.state.value.isCompleted)
        assertEquals(0L, vm.state.value.positionMs)
        assertTrue(vm.state.value.isPlaying)
    }

    @Test
    fun `seek is clamped to zero and known duration`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        engine.emitPrepared(65_000, hasVideo = true)
        advanceUntilIdle()

        vm.onSeekTo(-5_000)
        assertEquals(0L, engine.seeks.last())
        vm.onSeekTo(999_999)
        assertEquals("beyond duration clamps to duration", 65_000L, engine.seeks.last())
        vm.onSeekTo(20_000)
        assertEquals(20_000L, engine.seeks.last())
    }

    @Test
    fun `relative seek is clamped to the known duration bounds`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        engine.emitPrepared(65_000, hasVideo = true)
        advanceUntilIdle()

        vm.onSeekTo(60_000)
        vm.onSeekBy(10_000) // 60s + 10s → clamps to duration 65s
        assertEquals(65_000L, engine.seeks.last())

        vm.onSeekBy(-100_000) // far below zero → clamps to 0
        assertEquals(0L, engine.seeks.last())
    }

    @Test
    fun `relative seek with unknown duration only clamps the lower bound`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        engine.emitPrepared(0, hasVideo = false) // duration genuinely unknown
        advanceUntilIdle()

        vm.onSeekBy(-10_000) // position 0 + (-10s) → clamps to 0
        assertEquals(0L, engine.seeks.last())
    }

    @Test
    fun `transient focus loss pauses and the state mirrors it`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        engine.emitPrepared(65_000, hasVideo = true)
        advanceUntilIdle()
        assertTrue(vm.state.value.isPlaying)

        engine.emitTransientFocusLoss()
        advanceUntilIdle()

        assertFalse(vm.state.value.isPlaying)
        assertEquals(PlaybackPhase.READY, vm.state.value.phase)
    }

    @Test
    fun `focus regain resumes and permanent loss stays paused`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        engine.emitPrepared(65_000, hasVideo = true)
        advanceUntilIdle()

        engine.emitTransientFocusLoss()
        engine.emitFocusGainResume()
        advanceUntilIdle()
        assertTrue("auto-resume after transient loss", vm.state.value.isPlaying)

        engine.emitPermanentFocusLoss()
        advanceUntilIdle()
        assertFalse(vm.state.value.isPlaying)
    }

    @Test
    fun `becoming noisy pauses playback`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        engine.emitPrepared(65_000, hasVideo = true)
        advanceUntilIdle()
        assertTrue(vm.state.value.isPlaying)

        vm.onBecameNoisy()
        assertEquals(1, engine.paused)
        assertFalse(vm.state.value.isPlaying)
    }

    @Test
    fun `becoming noisy while not playing is a no-op`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        engine.emitPrepared(65_000, hasVideo = true)
        advanceUntilIdle()
        vm.onTogglePlay() // pause
        val pauses = engine.paused

        vm.onBecameNoisy()
        assertEquals(pauses, engine.paused)
    }

    // ===== P22-LOW-1: isPlaying mirrors the actual play() result =====

    @Test
    fun `audio-focus denial keeps the UI non-playing on auto-start`() = runTest {
        engine.playGranted = false
        val vm = viewModel()
        advanceUntilIdle()
        engine.emitPrepared(65_000, hasVideo = true)
        advanceUntilIdle()

        assertEquals(PlaybackPhase.READY, vm.state.value.phase)
        assertFalse("denied focus must not show a playing state", vm.state.value.isPlaying)
    }

    @Test
    fun `audio-focus denial on explicit play keeps the UI non-playing`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        engine.emitPrepared(65_000, hasVideo = true)
        advanceUntilIdle()

        engine.playGranted = false
        vm.onTogglePlay()
        assertFalse(vm.state.value.isPlaying)

        // Recovery: once focus is granted, the next explicit play works.
        engine.playGranted = true
        vm.onTogglePlay()
        assertTrue(vm.state.value.isPlaying)
    }

    @Test
    fun `audio-focus denial during replay keeps the UI non-playing but replayable`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        engine.emitPrepared(65_000, hasVideo = true)
        advanceUntilIdle()
        engine.listener?.onCompletion()
        advanceUntilIdle()
        assertTrue(vm.state.value.isCompleted)

        engine.playGranted = false
        vm.onTogglePlay() // replay attempt, focus denied
        assertFalse(vm.state.value.isPlaying)
        // The replay intent was still applied — position restarted; pressing
        // play again once focus is granted replays normally.
        assertTrue(engine.seeks.contains(0L))

        engine.playGranted = true
        vm.onTogglePlay()
        assertTrue(vm.state.value.isPlaying)
        assertFalse(vm.state.value.isCompleted)
    }

    @Test
    fun `successful play sets isPlaying true`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        engine.emitPrepared(65_000, hasVideo = true)
        advanceUntilIdle()
        assertTrue(vm.state.value.isPlaying) // auto-start granted

        vm.onTogglePlay() // pause
        assertFalse(vm.state.value.isPlaying)
        vm.onTogglePlay() // explicit play granted
        assertTrue(vm.state.value.isPlaying)
    }
}
