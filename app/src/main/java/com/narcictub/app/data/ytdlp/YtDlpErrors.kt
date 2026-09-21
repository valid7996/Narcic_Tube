package com.narcictub.app.data.ytdlp

import com.narcictub.app.domain.resolver.MediaResolveException.ExtractionFailed.Reason

/** The bundled yt-dlp / Python / ffmpeg runtime could not be started. */
internal class YtDlpEngineException(cause: Throwable) :
    Exception("download engine could not start", cause)

/**
 * Turns yt-dlp's stderr text into a coarse [Reason]. Only the classification
 * leaves this object — raw tool output (which can contain URLs or account
 * details) is never surfaced to the UI or logs.
 */
internal object YtDlpErrors {

    private val LOGIN = listOf(
        "login required", "log in", "sign in", "--cookies", "not a bot",
        "confirm your age", "empty media response", "rate-limit reached",
    )
    private val NO_MEDIA = listOf(
        "requested format is not available", "no video formats found",
        "there is no video in this post", "no formats", "unsupported url",
        "no video could be found",
    )
    private val RATE_LIMIT = listOf("429", "too many requests")
    private val UNAVAILABLE = listOf(
        "private video", "video unavailable", "video is unavailable", "not available",
        "has been removed", "does not exist", "members-only", "this video is private",
        "copyright", "blocked it", "account has been terminated", "deleted", "not found",
    )
    private val NETWORK = listOf(
        "timed out", "timeout", "urlopen error", "connection", "network is unreachable",
        "name or service not known", "temporary failure in name resolution",
        "unable to download webpage", "ssl",
    )

    fun reasonOf(error: Throwable): Reason {
        if (error is YtDlpEngineException) return Reason.ENGINE_UNAVAILABLE
        val text = generateSequence(error) { it.cause }
            .take(4)
            .mapNotNull { it.message }
            .joinToString(" ")
        return reasonOfText(text)
    }

    /** Order matters: "not available" must not swallow "format is not available". */
    fun reasonOfText(raw: String): Reason {
        val text = raw.lowercase()
        return when {
            LOGIN.any { it in text } -> Reason.LOGIN_REQUIRED
            NO_MEDIA.any { it in text } -> Reason.NO_MEDIA
            RATE_LIMIT.any { it in text } -> Reason.RATE_LIMITED
            UNAVAILABLE.any { it in text } -> Reason.UNAVAILABLE
            NETWORK.any { it in text } -> Reason.NETWORK
            else -> Reason.OTHER
        }
    }

    /** Fixed, safe sentences for a failed DOWNLOAD row (shown in the Downloads list). */
    fun downloadMessage(reason: Reason): String = when (reason) {
        Reason.LOGIN_REQUIRED -> "Login required — import your cookies.txt in Settings"
        Reason.UNAVAILABLE -> "This media is private, removed or blocked in your region"
        Reason.NO_MEDIA -> "No downloadable video was found"
        Reason.RATE_LIMITED -> "Too many requests — try again later"
        Reason.NETWORK -> "Network error during download"
        Reason.ENGINE_UNAVAILABLE -> "The download engine couldn't start — restart the app"
        Reason.OTHER -> "Download failed — the site may have changed; restart the app to update the engine"
    }
}
