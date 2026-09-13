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
 */
interface FileDownloader {
    suspend fun download(
        url: String,
        destination: File,
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
}
