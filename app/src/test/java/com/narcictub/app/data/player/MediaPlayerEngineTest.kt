package com.narcictub.app.data.player

import android.content.Context
import android.view.Surface
import com.narcictub.app.data.local.MediaUriSafety
import com.narcictub.app.domain.player.PlaybackEngine
import com.narcictub.app.domain.player.PlaybackErrorReason
import com.narcictub.app.domain.player.PlaybackSource
import io.mockk.mockk
import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * PHASE 11 — engine guard tests (JVM, scripted player ops): the LAST-LINE
 * URI policy before the framework player. Network/custom schemes, traversal
 * escapes and malformed input are rejected BEFORE any player is created;
 * only content:// and app-contained file:// sources reach preparation.
 */
class MediaPlayerEngineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class RecordingErrors : PlaybackEngine.Listener {
        val prepared = mutableListOf<Pair<Long, Boolean>>()
        val errors = mutableListOf<PlaybackErrorReason>()
        val videoSizes = mutableListOf<Pair<Int, Int>>()
        var completions = 0
        var transientLossPauses = 0
        var permanentLossPauses = 0
        var focusGainResumes = 0

        override fun onPrepared(durationMs: Long, hasVideo: Boolean) {
            prepared.add(durationMs to hasVideo)
        }
        override fun onVideoSizeChanged(width: Int, height: Int) {
            videoSizes.add(width to height)
        }
        override fun onBuffering(active: Boolean) {}
        override fun onCompletion() {
            completions++
        }
        override fun onError(reason: PlaybackErrorReason) {
            errors.add(reason)
        }
        override fun onPausedByTransientFocusLoss() {
            transientLossPauses++
        }
        override fun onPausedByPermanentFocusLoss() {
            permanentLossPauses++
        }
        override fun onResumedByFocusGain() {
            focusGainResumes++
        }
    }

    private class FakeOps : MediaPlayerOps {
        var dataSources = mutableListOf<String>()
        var preparedAsync = 0
        var started = 0
        var paused = 0
        var seeks = mutableListOf<Int>()
        var releases = 0
        var surfaces = mutableListOf<Any?>()
        var failSetDataSource = false
        var currentPosition: Int? = null
        var adapter: MediaPlayerOps.Adapter? = null

        // PHASE 22: audio-focus recording.
        var focusGranted = true
        var focusRequests = 0
        var focusAbandons = 0

        override fun setListenerAdapter(adapter: MediaPlayerOps.Adapter) {
            this.adapter = adapter
        }
        override fun setAudioAttributesForPlayback() {}
        override fun setDataSource(context: Context, uriText: String) {
            if (failSetDataSource) throw IOException("boom")
            dataSources.add(uriText)
        }
        override fun prepareAsync() {
            preparedAsync++
        }
        override fun setSurface(surface: Surface?) {
            surfaces.add(surface)
        }
        override fun start() {
            started++
        }
        override fun pause() {
            paused++
        }
        override fun seekTo(positionMs: Int) {
            seeks.add(positionMs)
        }
        override val currentPositionMs: Int?
            get() = currentPosition
        override fun release() {
            releases++
        }
        override fun requestAudioFocus(): Boolean {
            focusRequests++
            return focusGranted
        }
        override fun abandonAudioFocus() {
            focusAbandons++
        }
    }

    private class GuardedEngine(appDirs: List<File>) : MediaPlayerEngine(
        context = mockk<Context>(relaxed = true),
        uriSafety = MediaUriSafety(appDirs.map { it.canonicalFile }),
    ) {
        val created = mutableListOf<FakeOps>()
        var failNextSetDataSource = false

        init {
            opsFactory = {
                FakeOps().also {
                    if (failNextSetDataSource) it.failSetDataSource = true
                    created.add(it)
                }
            }
        }
    }

    private fun engineWithAppDir(): Pair<GuardedEngine, File> {
        val appDir = tmp.newFolder("app")
        return GuardedEngine(listOf(appDir)) to appDir
    }

    @Test
    fun `http and https sources are rejected before any player exists`() {
        val engine = GuardedEngine(emptyList())
        val errors = RecordingErrors()

        engine.prepare(PlaybackSource("https://cdn.example.com/video.mp4", "video/mp4"), errors)
        engine.prepare(PlaybackSource("http://cdn.example.com/video.mp4", "video/mp4"), errors)

        assertEquals(
            listOf(PlaybackErrorReason.INVALID_SOURCE, PlaybackErrorReason.INVALID_SOURCE),
            errors.errors,
        )
        assertTrue("no player may be created for network sources", engine.created.isEmpty())
    }

    @Test
    fun `custom schemes and malformed uris are rejected`() {
        val engine = GuardedEngine(emptyList())
        val errors = RecordingErrors()

        for (uri in listOf(
            "ftp://example.com/f.mp4",
            "javascript:alert(1)",
            "data:video/mp4;base64,AAAA",
            "not a uri",
            "",
            "content:///no-authority",
            "content://user:pass@media/x",
        )) {
            engine.prepare(PlaybackSource(uri, null), errors)
        }

        assertEquals(7, errors.errors.size)
        assertTrue(errors.errors.all { it == PlaybackErrorReason.INVALID_SOURCE })
        assertTrue(engine.created.isEmpty())
    }

    @Test
    fun `file uri outside the app tree is rejected before preparation`() {
        val (engine, _) = engineWithAppDir()
        val outside = tmp.newFolder("outside")
        val errors = RecordingErrors()

        engine.prepare(
            PlaybackSource(File(outside, "secret.bin").toURI().toString(), null),
            errors,
        )

        assertEquals(PlaybackErrorReason.INVALID_SOURCE, errors.errors.single())
        assertTrue(engine.created.isEmpty())
    }

    @Test
    fun `content uri is prepared through the standard mechanism`() {
        val (engine, _) = engineWithAppDir()
        val errors = RecordingErrors()

        engine.prepare(
            PlaybackSource("content://media/external/downloads/9", "video/mp4"),
            errors,
        )

        assertTrue(errors.errors.isEmpty())
        val ops = engine.created.single()
        assertEquals("content://media/external/downloads/9", ops.dataSources.single())
        assertEquals(1, ops.preparedAsync)
    }

    @Test
    fun `app-owned file uri is prepared without path conversion outside policy`() {
        val (engine, appDir) = engineWithAppDir()
        val errors = RecordingErrors()
        val media = File(appDir, "downloads/clip.mp4").also { it.parentFile.mkdirs() }

        engine.prepare(PlaybackSource(media.toURI().toString(), "video/mp4"), errors)

        assertTrue(errors.errors.isEmpty())
        val ops = engine.created.single()
        assertEquals(1, ops.preparedAsync)
        assertEquals(media.toURI().toString(), ops.dataSources.single())
    }

    @Test
    fun `setDataSource failure maps to a safe reason and releases the player`() {
        val (engine, _) = engineWithAppDir()
        engine.failNextSetDataSource = true
        val errors = RecordingErrors()

        engine.prepare(
            PlaybackSource("content://media/external/downloads/9", "video/mp4"),
            errors,
        )

        assertEquals(PlaybackErrorReason.READ_FAILED, errors.errors.single())
        assertEquals(1, engine.created.single().releases)
    }

    @Test
    fun `second prepare releases the first player - one player only`() {
        val (engine, _) = engineWithAppDir()
        val errors = RecordingErrors()

        engine.prepare(PlaybackSource("content://media/external/downloads/1", null), errors)
        engine.prepare(PlaybackSource("content://media/external/downloads/2", null), errors)

        assertEquals(2, engine.created.size)
        assertEquals("previous player must be released", 1, engine.created[0].releases)
        assertEquals(0, engine.created[1].releases)
    }

    @Test
    fun `controls and callbacks delegate`() {
        val (engine, _) = engineWithAppDir()
        val errors = RecordingErrors()
        engine.prepare(PlaybackSource("content://media/external/downloads/1", null), errors)
        val ops = engine.created.single()

        engine.play()
        engine.pause()
        engine.seekTo(1500)
        engine.attachSurface(null)

        assertEquals(1, ops.started)
        assertEquals(1, ops.paused)
        assertEquals(listOf(1500), ops.seeks)
        assertEquals(listOf<Any?>(null), ops.surfaces)

        // Callbacks from the framework player reach the listener (while live).
        ops.adapter?.onPrepared(65_000, true)
        ops.adapter?.onVideoSizeChanged(1920, 1080)
        ops.adapter?.onCompletion()

        // Release LAST: after teardown the engine correctly drops callbacks,
        // so a released player can never update a dead screen.
        engine.release()
        assertEquals(1, ops.releases)
        assertEquals(1, engine.created[0].releases)

        val beforeTeardown = errors.prepared.size
        ops.adapter?.onPrepared(1, false)
        assertEquals("no callbacks after release", beforeTeardown, errors.prepared.size)

        assertEquals(65_000L to true, errors.prepared.single())
        assertEquals(1920 to 1080, errors.videoSizes.single())
        assertEquals(1, errors.completions)
    }

    @Test
    fun `current position forwards when queryable`() {
        val (engine, _) = engineWithAppDir()
        val errors = RecordingErrors()
        engine.prepare(PlaybackSource("content://media/external/downloads/1", null), errors)
        val ops = engine.created.single()
        assertNull(engine.currentPositionMs)

        ops.currentPosition = 4_200
        assertEquals(4_200L, engine.currentPositionMs)
    }

    // ===== Phase 22: audio focus =====

    @Test
    fun `play requests audio focus and denied focus never starts playback`() {
        val (engine, _) = engineWithAppDir()
        val errors = RecordingErrors()
        engine.prepare(PlaybackSource("content://media/external/downloads/1", null), errors)
        val ops = engine.created.single()

        ops.focusGranted = false
        val denied = engine.play()

        assertEquals(1, ops.focusRequests)
        assertFalse("denied focus must report no start", denied)
        assertEquals("focus denied — playback must not start", 0, ops.started)

        ops.focusGranted = true
        val granted = engine.play()
        assertTrue("granted focus reports an actual start", granted)
        assertEquals("each start requests focus once", 2, ops.focusRequests)
        assertEquals(1, ops.started)
    }

    @Test
    fun `user pause abandons audio focus`() {
        val (engine, _) = engineWithAppDir()
        val errors = RecordingErrors()
        engine.prepare(PlaybackSource("content://media/external/downloads/1", null), errors)
        val ops = engine.created.single()

        engine.play()
        assertEquals(0, ops.focusAbandons)
        engine.pause()
        assertEquals(1, ops.focusAbandons)
    }

    @Test
    fun `transient focus loss pauses and resumes automatically on regain`() {
        val (engine, _) = engineWithAppDir()
        val errors = RecordingErrors()
        engine.prepare(PlaybackSource("content://media/external/downloads/1", null), errors)
        val ops = engine.created.single()
        engine.play()

        ops.adapter?.onFocusLossTransient()

        assertEquals("transient loss pauses the player", 1, ops.paused)
        assertEquals(1, errors.transientLossPauses)

        ops.adapter?.onFocusGain()

        assertEquals("focus regained — playback auto-resumes", 2, ops.started)
        assertEquals(1, errors.focusGainResumes)
    }

    @Test
    fun `permanent focus loss pauses without auto-resume`() {
        val (engine, _) = engineWithAppDir()
        val errors = RecordingErrors()
        engine.prepare(PlaybackSource("content://media/external/downloads/1", null), errors)
        val ops = engine.created.single()
        engine.play()

        ops.adapter?.onFocusLossPermanent()

        assertEquals(1, ops.paused)
        assertEquals(1, errors.permanentLossPauses)

        ops.adapter?.onFocusGain()

        assertEquals("no auto-resume after permanent loss", 1, ops.started)
        assertEquals(0, errors.focusGainResumes)
    }

    @Test
    fun `focus loss while not playing does not pause or notify`() {
        val (engine, _) = engineWithAppDir()
        val errors = RecordingErrors()
        engine.prepare(PlaybackSource("content://media/external/downloads/1", null), errors)
        val ops = engine.created.single()

        ops.adapter?.onFocusLossTransient()
        ops.adapter?.onFocusLossPermanent()

        assertEquals(0, ops.paused)
        assertEquals(0, errors.transientLossPauses)
        assertEquals(0, errors.permanentLossPauses)
    }

    @Test
    fun `completed playback no longer responds to transient loss`() {
        val (engine, _) = engineWithAppDir()
        val errors = RecordingErrors()
        engine.prepare(PlaybackSource("content://media/external/downloads/1", null), errors)
        val ops = engine.created.single()
        engine.play()
        ops.adapter?.onCompletion()

        ops.adapter?.onFocusLossTransient()

        assertEquals("playback already finished — nothing to pause", 0, ops.paused)
    }

    @Test
    fun `release abandons audio focus`() {
        val (engine, _) = engineWithAppDir()
        val errors = RecordingErrors()
        engine.prepare(PlaybackSource("content://media/external/downloads/1", null), errors)
        val ops = engine.created.single()
        engine.play()

        engine.release()

        assertEquals(1, ops.focusAbandons)
    }
}
