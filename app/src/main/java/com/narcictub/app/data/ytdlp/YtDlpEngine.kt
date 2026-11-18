package com.narcictub.app.data.ytdlp

import android.content.Context
import com.narcictub.app.domain.model.MediaInfo
import com.narcictub.app.domain.model.YoutubeSearchItem
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Outcome of a yt-dlp engine update. The version is the tag stored by
 * youtubedl-android (null when the bundled engine was never updated).
 *
 * v1.3.2 — the startup update used to fail silently (offline / GitHub
 * rate-limited: the unauthenticated releases API allows 60 requests/hour
 * per IP), leaving the bundled 2025 engine to fail every Instagram link
 * with a generic error. The outcome is now recorded and surfaced in
 * Settings, so a failed update is visible instead of invisible.
 */
sealed interface EngineUpdateStatus {
    data object NotAttempted : EngineUpdateStatus
    data object Checking : EngineUpdateStatus
    data class UpToDate(val version: String?) : EngineUpdateStatus
    data class Updated(val version: String?) : EngineUpdateStatus
    data object Failed : EngineUpdateStatus
}

/**
 * The ONLY class that touches the youtubedl-android library (a wrapper that
 * runs a bundled Python + yt-dlp + ffmpeg as child processes). Everything
 * else in the app talks to this seam, which keeps the rest unit-testable
 * (tests mock this class) and makes the library swappable.
 *
 * Lifecycle: [warmUp] runs once at app start (extracts the bundled runtime —
 * slow the very first time — then tries to update yt-dlp, which is what keeps
 * YouTube working when the site changes). [initialize]/[awaitReady] are what
 * requests call; they are idempotent and wait for warm-up (bounded).
 *
 * Only YouTube / Instagram URLs may reach [dumpJson] / [download]; the callers
 * validate the host (YtDlpUrl) — yt-dlp's generic extractor would otherwise
 * fetch anything, including local-network addresses.
 */
@Singleton
open class YtDlpEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val initMutex = Mutex()

    @Volatile private var initialized = false

    @Volatile private var warmUpStarted = false
    private val warmUpDone = CompletableDeferred<Unit>()

    /** Last engine-update outcome; observed by Settings. Starts unattempted. */
    private val _updateStatus = MutableStateFlow<EngineUpdateStatus>(EngineUpdateStatus.NotAttempted)
    open val updateStatus: StateFlow<EngineUpdateStatus> = _updateStatus.asStateFlow()

    /** yt-dlp version tag known to the app (null = bundled version, never updated). */
    open fun currentVersion(): String? =
        runCatching { YoutubeDL.getInstance().version(context) }.getOrNull()

    /** Initializes yt-dlp + ffmpeg once. Throws [YtDlpEngineException] on failure (retried next call). */
    open suspend fun initialize() {
        if (initialized) return
        initMutex.withLock {
            if (initialized) return
            try {
                withContext(Dispatchers.IO) {
                    YoutubeDL.getInstance().init(context)
                    FFmpeg.getInstance().init(context)
                }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                throw YtDlpEngineException(e)
            }
            initialized = true
        }
    }

    /** App-start preparation: init, then a best-effort yt-dlp update. Never throws. */
    open suspend fun warmUp() {
        warmUpStarted = true
        try {
            initialize()
            // آپدیت دوباره فعال شد (v1.2.0 — اصلاح v1.1.2): موتور قدیمیِ
            // باندل‌شده در برابر اینستاگرامِ ۲۰۲۶ مُرد (پیام login برای همه
            // پست‌ها)؛ موتور آپدیت‌شده ویدیو/ریلز اینستاگرام را بدون کوکی
            // می‌گیرد. اولین باز شدن با اینترنت/VPN طول می‌کشد؛ اگر آپدیت
            // ناموفق بود، همان نسخه باندل‌شده استفاده می‌شود.
            // v1.3.2: the outcome is recorded in [updateStatus] (and retried
            // once) instead of vanishing — see [updateEngine].
            updateEngine()
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
        } finally {
            warmUpDone.complete(Unit)
        }
    }

    /**
     * Runs the stable-channel yt-dlp update and records the outcome in
     * [updateStatus]. Never throws (except cancellation): a failed attempt is
     * retried once for transient network blips, then reported as [Failed] so
     * Settings can show it and the user can retry by hand.
     */
    open suspend fun updateEngine(): EngineUpdateStatus {
        _updateStatus.value = EngineUpdateStatus.Checking
        var result = attemptUpdate()
        if (result is EngineUpdateStatus.Failed) {
            delay(UPDATE_RETRY_DELAY_MS)
            result = attemptUpdate()
        }
        _updateStatus.value = result
        return result
    }

    private suspend fun attemptUpdate(): EngineUpdateStatus {
        return try {
            val status = withContext(Dispatchers.IO) {
                YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel._STABLE)
            }
            val version = currentVersion()
            if (status == YoutubeDL.UpdateStatus.DONE) EngineUpdateStatus.Updated(version)
            else EngineUpdateStatus.UpToDate(version)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Offline / GitHub rate-limited: keep the bundled version.
            EngineUpdateStatus.Failed
        }
    }

    private suspend fun awaitReady() {
        initialize()
        // A running warm-up may be swapping yt-dlp files; give it a bounded
        // moment to finish so a request doesn't race an update.
        if (warmUpStarted) withTimeoutOrNull(WARM_UP_WAIT_MS) { warmUpDone.await() }
    }

    /**
     * Runs `yt-dlp --dump-single-json` and returns the raw JSON (stdout).
     * Throws on failure; the exception text is yt-dlp's stderr and must only
     * be classified (YtDlpErrors), never displayed.
     */
    open suspend fun dumpJson(url: String): String {
        awaitReady()
        val request = YoutubeDLRequest(url)
        request.addOption("--dump-single-json")
        request.addOption("--no-warnings")
        request.addOption("--no-playlist")
        request.addOption("--playlist-items", "1")
        request.addOption("--socket-timeout", "30")
        applyCommonOptions(request)
        return runDumpJson(request, "narcictub-resolve-")
    }

    /**
     * HONEY — جستجوی یوتیوب از طریق خود موتور (`ytsearchN:query`): بدون API
     * key و بدون سرویس جانبی. فهرست تخت (flat playlist) برمی‌گردد.
     */
    open suspend fun search(query: String, limit: Int): List<YoutubeSearchItem> {
        awaitReady()
        val request = YoutubeDLRequest("ytsearch$limit:${query.trim()}")
        request.addOption("--flat-playlist")
        request.addOption("--dump-single-json")
        request.addOption("--no-warnings")
        request.addOption("--socket-timeout", "30")
        applyCommonOptions(request)
        val raw = try {
            runDumpJson(request, "narcictub-search-")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw YtDlpEngineException(e)
        }
        return YtDlpSearchParser.parse(raw)
    }

    private suspend fun runDumpJson(request: YoutubeDLRequest, processIdBase: String): String {
        val processId = processIdBase + System.nanoTime()
        val ignoreProgress: (Float, Long, String) -> Unit = { _, _, _ -> }
        return try {
            runInterruptible(Dispatchers.IO) {
                YoutubeDL.getInstance().execute(request, processId, ignoreProgress)
            }.out
        } catch (e: CancellationException) {
            runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
            throw e
        }
    }

    /**
     * Downloads [url] into [outputDir] as `media.<ext>` ([formatSelector] is a
     * yt-dlp format id or `videoId+audioId`, already validated by the caller;
     * null lets yt-dlp choose). Blocks until the child process ends;
     * coroutine cancellation kills the process. Progress is reported as
     * (percent, raw yt-dlp line).
     */
    open suspend fun download(
        url: String,
        formatSelector: String?,
        outputDir: File,
        processId: String,
        onProgress: (percent: Float, line: String) -> Unit,
    ) {
        awaitReady()
        val request = YoutubeDLRequest(url)
        request.addOption("--no-playlist")
        request.addOption("--playlist-items", "1")
        request.addOption("--no-mtime")
        request.addOption("--socket-timeout", "30")
        request.addOption("-o", File(outputDir, "media.%(ext)s").absolutePath)
        if (formatSelector != null) request.addOption("-f", formatSelector)
        applyCommonOptions(request)

        val callback: (Float, Long, String) -> Unit = { percent, _, line -> onProgress(percent, line) }
        try {
            runInterruptible(Dispatchers.IO) {
                YoutubeDL.getInstance().execute(request, processId, callback)
            }
        } catch (e: CancellationException) {
            runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
            throw e
        }
    }

    private fun applyCommonOptions(request: YoutubeDLRequest) {
        // No --extractor-args here, deliberately: yt-dlp's default client set
        // returns the full https DASH format list (all heights + audio-only),
        // and logs in are not required for regular videos. Alternative
        // clients (android_vr/tv/ios) were tried and REGRESS hard: they
        // return HLS-only streams, which collapse the variant list to the
        // single 360p muxed format and fail to download (PO-token gating).
        // Optional login session (Settings → import cookies.txt) still
        // applies on top for age-restricted/private media.
        if (YtDlpCookies.exists(context)) {
            request.addOption("--cookies", YtDlpCookies.file(context).absolutePath)
        }
        // Optional JavaScript runtime for YouTube (see README). Only passed when the
        // binary is actually packaged, so builds without it behave as before.
        val deno = File(context.applicationInfo.nativeLibraryDir, DENO_LIBRARY_NAME)
        if (deno.isFile) request.addOption("--js-runtimes", "deno:" + deno.absolutePath)
    }

    private companion object {
        /** Keeps the engine current against platform changes (v1.2.0). */
        const val AUTO_UPDATE_ON_LAUNCH = true
        const val WARM_UP_WAIT_MS = 20_000L
        const val DENO_LIBRARY_NAME = "libdeno.so"
        /** One retry for transient update failures (v1.3.2). */
        const val UPDATE_RETRY_DELAY_MS = 3_000L
    }
}
