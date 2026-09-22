package com.narcictub.app.domain.downloader

import com.narcictub.app.domain.model.DownloadProgress
import java.io.File

/**
 * Result of a completed download. Byte counts are the bytes actually written
 * to the staging file; [contentType] is the server-declared MIME type if any.
 */
data class DownloadFileResult(
    val bytesDownloaded: Long,
    val contentType: String? = null,
    /**
     * Real file extension (without dot) when the transport itself decides the
     * container — e.g. yt-dlp producing mp4/m4a/webm. Null for plain HTTP
     * downloads, whose display name already comes from the URL.
     */
    val fileExtension: String? = null,
)

/**
 * Transport contract for streaming a file from an http/https URL to a local
 * staging file. Implementations live in the data layer.
 *
 * Contract:
 *  - streams bytes; never loads the whole body into memory
 *  - reports real progress via [onProgress] (totalBytes null when unknown)
 *  - propagates coroutine cancellation as CancellationException (never wraps it)
 *  - reports every other failure as a [DownloadException]
 *  - [resumeFromBytes]: bytes already sitting in [destination] from a
 *    previous, paused attempt at this SAME id. An implementation that can
 *    honor it appends starting there (e.g. an HTTP Range request); one that
 *    cannot MUST still produce a complete, correct file — falling back to a
 *    full restart is always a valid (if less efficient) implementation of
 *    this contract, never a correctness bug. [DownloadFileResult.bytesDownloaded]
 *    is always the file's real FINAL size, resumed or not.
 */
interface FileDownloader {
    suspend fun download(
        url: String,
        destination: File,
        resumeFromBytes: Long = 0L,
        onProgress: (DownloadProgress) -> Unit,
    ): DownloadFileResult
}

/**
 * Typed download failures. Messages deliberately contain no URL, headers or
 * response-body content so they can surface in the UI/logs safely.
 */
sealed class DownloadException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Network(cause: Throwable) : DownloadException("Network error during download", cause)
    class Http(val statusCode: Int) : DownloadException("HTTP error $statusCode")
    class Io(cause: Throwable) : DownloadException("File error during download", cause)
    class Policy(message: String) : DownloadException(message)

    /** yt-dlp (YouTube/Instagram) could not download; [message] is a fixed, safe sentence. */
    class Extraction(message: String) : DownloadException(message)
}
