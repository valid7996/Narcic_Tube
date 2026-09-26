package com.narcictub.app.data.ytdlp

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

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
            // AUTO-UPDATE قطع شد (v1.1.2): yt-dlpهای جدیدِ ۲۰۲۶ برای یوتیوب/
            // اینستاگرام/تیک‌تاک درخواست ورود می‌دهند (PO token / bot check) —
            // نسخه باندل‌شده داخل APK همین الان هم بدون کوکی دانلود می‌کند
            // و کارکرد آن «قطعی» است، نه وابسته به اینکه آخرین آپدیت گیت‌هاب
            // موفق شده یا نه. اگر روزی آپدیت لازم شد، این پرچم برگردد.
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
        } finally {
            warmUpDone.complete(Unit)
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

        val processId = "narcictub-resolve-" + System.nanoTime()
        val ignoreProgress: (Float, Long, String) -> Unit = { _, _, _ -> }
        try {
            val response = runInterruptible(Dispatchers.IO) {
                YoutubeDL.getInstance().execute(request, processId, ignoreProgress)
            }
            return response.out
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
        /** Keeps bundled yt-dlp current (YouTube changes often). Flip to false to disable. */
        const val AUTO_UPDATE_ON_LAUNCH = false
        const val WARM_UP_WAIT_MS = 20_000L
        const val DENO_LIBRARY_NAME = "libdeno.so"
    }
}
