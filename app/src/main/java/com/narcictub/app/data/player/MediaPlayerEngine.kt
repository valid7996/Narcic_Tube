package com.narcictub.app.data.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.view.Surface
import com.narcictub.app.data.local.MediaUriSafety
import com.narcictub.app.domain.player.PlaybackEngine
import com.narcictub.app.domain.player.PlaybackErrorReason
import com.narcictub.app.domain.player.PlaybackSource
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * PHASE 11 — framework [MediaPlayer]-backed engine for LOCAL playback only.
 *
 * SECURITY (URI guard, last line of defense): every source passes through
 * the SAME [MediaUriSafety] policy used at validation time immediately
 * before it reaches the player. Only content:// URIs with a valid authority
 * and app-contained file:// URIs (the pre-Q publisher form) are playable —
 * http/https/ftp/custom schemes, traversal escapes and malformed input are
 * rejected with INVALID_SOURCE and NEVER reach the framework player. There
 * is no network data source and no path conversion for content URIs (the
 * platform's standard setDataSource(Context, Uri) mechanism is used).
 *
 * LIFECYCLE: the owning ViewModel creates exactly one engine (one player)
 * and releases it on teardown; a second prepare() releases the previous
 * player first, so no player can accumulate.
 */
open class MediaPlayerEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val uriSafety: MediaUriSafety,
) : PlaybackEngine {

    /** JVM-testable seam around the framework player. */
    internal var opsFactory: () -> MediaPlayerOps = { RealMediaPlayerOps(context) }
    private var ops: MediaPlayerOps? = null
    private var listener: PlaybackEngine.Listener? = null

    /** PHASE 22: engine-side playback mirror for focus-resume decisions. */
    private var playing = false
    private var resumeOnFocusGain = false

    private val adapter = object : MediaPlayerOps.Adapter {
        override fun onPrepared(durationMs: Long, hasVideo: Boolean) {
            listener?.onPrepared(durationMs, hasVideo)
        }

        override fun onVideoSizeChanged(width: Int, height: Int) {
            listener?.onVideoSizeChanged(width, height)
        }

        override fun onBuffering(active: Boolean) {
            listener?.onBuffering(active)
        }

        override fun onCompletion() {
            playing = false
            listener?.onCompletion()
        }

        override fun onError(reason: PlaybackErrorReason) {
            playing = false
            listener?.onError(reason)
        }

        override fun onFocusLossTransient() {
            // Pause for the interruption and resume automatically once the
            // short-lived focus holder releases (standard platform nicety).
            if (playing) {
                ops?.pause()
                playing = false
                resumeOnFocusGain = true
                listener?.onPausedByTransientFocusLoss()
            }
        }

        override fun onFocusLossPermanent() {
            // Another app owns audio now: pause, never auto-resume.
            if (playing) {
                ops?.pause()
                playing = false
                listener?.onPausedByPermanentFocusLoss()
            }
            resumeOnFocusGain = false
        }

        override fun onFocusGain() {
            if (resumeOnFocusGain) {
                resumeOnFocusGain = false
                ops?.start()
                playing = true
                listener?.onResumedByFocusGain()
            }
        }
    }

    override fun prepare(source: PlaybackSource, listener: PlaybackEngine.Listener) {
        this.listener = listener

        // URI guard — the exact same policy as validation time. Rejection
        // happens BEFORE any player exists and before the source is touched.
        if (uriSafety.classify(source.uriText) == MediaUriSafety.Kind.UNSAFE) {
            listener.onError(PlaybackErrorReason.INVALID_SOURCE)
            return
        }

        // Single-player guarantee: a re-prepare releases the previous player.
        ops?.release()
        val newOps = opsFactory()
        ops = newOps
        try {
            newOps.setListenerAdapter(adapter)
            newOps.setAudioAttributesForPlayback()
            // Standard platform mechanism for BOTH accepted URI classes —
            // content:// is resolved by the provider; no path extraction.
            newOps.setDataSource(context, source.uriText)
            newOps.prepareAsync()
        } catch (_: IllegalStateException) {
            release()
            listener?.onError(PlaybackErrorReason.READ_FAILED)
        } catch (_: java.io.IOException) {
            release()
            listener?.onError(PlaybackErrorReason.READ_FAILED)
        } catch (_: SecurityException) {
            release()
            listener?.onError(PlaybackErrorReason.READ_FAILED)
        } catch (_: IllegalArgumentException) {
            release()
            listener?.onError(PlaybackErrorReason.INVALID_SOURCE)
        }
    }

    override fun attachSurface(surface: Any?) {
        ops?.setSurface(surface as? Surface)
    }

    override fun play(): Boolean {
        // PHASE 22: audio focus is requested before every start; a denied
        // request means we simply do not begin — never steal audio. The
        // Boolean result lets the caller keep isPlaying in sync with
        // reality (P22-LOW-1) instead of assuming the start succeeded.
        if (ops?.requestAudioFocus() != true) return false
        ops?.start()
        playing = true
        return true
    }

    override fun pause() {
        ops?.pause()
        playing = false
        resumeOnFocusGain = false
        ops?.abandonAudioFocus()
    }

    override fun seekTo(positionMs: Long) {
        ops?.seekTo(positionMs.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt())
    }

    override val currentPositionMs: Long?
        get() = ops?.currentPositionMs?.toLong()

    override fun release() {
        ops?.abandonAudioFocus()
        ops?.release()
        ops = null
        playing = false
        resumeOnFocusGain = false
        listener = null
    }
}

/**
 * Minimal surface over the framework player so the engine's guard and
 * delegation logic is unit-testable on the JVM. Contains no Q+ symbols.
 */
internal interface MediaPlayerOps {
    fun setListenerAdapter(adapter: Adapter)
    fun setAudioAttributesForPlayback()
    fun setDataSource(context: Context, uriText: String)
    fun prepareAsync()
    fun setSurface(surface: Surface?)
    fun start()
    fun pause()
    fun seekTo(positionMs: Int)
    val currentPositionMs: Int?
    fun release()

    /**
     * PHASE 22: audio focus. [requestAudioFocus] returns true when focus is
     * granted (playback may start); the adapter delivers focus-loss/gain
     * callbacks. [abandonAudioFocus] releases it.
     */
    fun requestAudioFocus(): Boolean
    fun abandonAudioFocus()

    /** Callback adapter mapped from the framework player's listeners. */
    interface Adapter {
        fun onPrepared(durationMs: Long, hasVideo: Boolean)
        fun onVideoSizeChanged(width: Int, height: Int)
        fun onBuffering(active: Boolean)
        fun onCompletion()
        fun onError(reason: PlaybackErrorReason)
        fun onFocusLossTransient()
        fun onFocusLossPermanent()
        fun onFocusGain()
    }
}

internal class RealMediaPlayerOps(private val context: Context) : MediaPlayerOps {

    private val player = MediaPlayer()
    private var adapter: MediaPlayerOps.Adapter? = null
    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** PHASE 22: focus request built once (minSdk 26 → AudioFocusRequest). */
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
                .build(),
        )
        .setOnAudioFocusChangeListener { focusChange ->
            when (focusChange) {
                AudioManager.AUDIOFOCUS_LOSS ->
                    adapter?.onFocusLossPermanent()
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
                -> adapter?.onFocusLossTransient()
                AudioManager.AUDIOFOCUS_GAIN -> adapter?.onFocusGain()
            }
        }
        .build()

    override fun setListenerAdapter(adapter: MediaPlayerOps.Adapter) {
        this.adapter = adapter
        player.setOnPreparedListener { mp ->
            val duration = if (mp.duration > 0) mp.duration.toLong() else 0L
            // Real content truth: a video track exists in the decoded media.
            val hasVideo = try {
                mp.trackInfo?.any {
                    it.trackType == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_VIDEO
                } == true
            } catch (_: Exception) {
                false // track metadata unavailable — the VM falls back to MIME
            }
            adapter.onPrepared(duration, hasVideo)
        }
        player.setOnVideoSizeChangedListener { _, width, height ->
            adapter.onVideoSizeChanged(width, height)
        }
        player.setOnInfoListener { _, what, _ ->
            when (what) {
                MediaPlayer.MEDIA_INFO_BUFFERING_START -> adapter.onBuffering(true)
                MediaPlayer.MEDIA_INFO_BUFFERING_END -> adapter.onBuffering(false)
            }
            true
        }
        player.setOnCompletionListener { adapter.onCompletion() }
        player.setOnErrorListener { _, what, _ ->
            val reason = when (what) {
                MediaPlayer.MEDIA_ERROR_UNSUPPORTED,
                MediaPlayer.MEDIA_ERROR_MALFORMED,
                MediaPlayer.MEDIA_ERROR_NOT_VALID_FOR_PROGRESSIVE_PLAYBACK,
                -> PlaybackErrorReason.UNSUPPORTED_FORMAT
                MediaPlayer.MEDIA_ERROR_IO -> PlaybackErrorReason.READ_FAILED
                else -> PlaybackErrorReason.GENERIC
            }
            adapter.onError(reason)
            true // handled — the platform must not show its own error dialog
        }
    }

    override fun setAudioAttributesForPlayback() {
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
                .build(),
        )
    }

    override fun setDataSource(context: Context, uriText: String) {
        // Framework parsing happens here, behind the test seam — the engine
        // core itself stays JVM-testable and URI-agnostic.
        player.setDataSource(context, Uri.parse(uriText))
    }

    override fun requestAudioFocus(): Boolean =
        audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED

    override fun abandonAudioFocus() {
        audioManager.abandonAudioFocusRequest(focusRequest)
    }

    override fun prepareAsync() {
        player.prepareAsync()
    }

    override fun setSurface(surface: Surface?) {
        player.setSurface(surface)
    }

    override fun start() {
        player.start()
    }

    override fun pause() {
        player.pause()
    }

    override fun seekTo(positionMs: Int) {
        player.seekTo(positionMs)
    }

    override val currentPositionMs: Int?
        get() = try {
            player.currentPosition
        } catch (_: IllegalStateException) {
            null
        }

    override fun release() {
        try {
            player.release()
        } catch (_: Exception) {
            // releasing a never/prepared player must not crash teardown
        }
    }
}
