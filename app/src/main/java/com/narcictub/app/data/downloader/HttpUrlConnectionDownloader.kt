package com.narcictub.app.data.downloader

import com.narcictub.app.domain.NetworkDestinationPolicy
import com.narcictub.app.domain.downloader.DownloadException
import com.narcictub.app.domain.downloader.DownloadFileResult
import com.narcictub.app.domain.downloader.FileDownloader
import com.narcictub.app.domain.model.DownloadProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * HttpURLConnection-based streaming downloader.
 *
 * Concurrency model: the caller owns the coroutine (structured concurrency)
 * — this class starts no jobs, threads or timers itself. All blocking I/O
 * runs on Dispatchers.IO; cancellation is observed both at suspend points
 * and inside the copy loop (via coroutineContext.ensureActive), which also
 * causes HttpURLConnection reads to abort with an IOException that we
 * re-check for cancellation so CANCELLED is distinguishable from FAILED.
 *
 * Security: every URL — including redirect targets — passes through
 * [NetworkDestinationPolicy] immediately before the connection is opened.
 * Nothing about the request (URL, headers, body) is ever logged.
 */
@Singleton
class HttpUrlConnectionDownloader @Inject constructor() : FileDownloader {

    override suspend fun download(
        url: String,
        destination: File,
        onProgress: (DownloadProgress) -> Unit,
    ): DownloadFileResult = withContext(Dispatchers.IO) {
        var currentUrl = url
        var redirects = 0
        var connection: HttpURLConnection? = null

        try {
            while (true) {
                // Policy gate BEFORE every connection attempt — original URL
                // and every redirect target alike.
                NetworkDestinationPolicy.disallowedReason(currentUrl)?.let { reason ->
                    throw DownloadException.Policy("Destination blocked: $reason")
                }

                connection = open(currentUrl)
                val status = connection.responseCode

                when {
                    status in 200..299 -> {
                        val result = copyBody(connection, destination, onProgress)
                        return@withContext result
                    }
                    status in 300..399 -> {
                        redirects += 1
                        if (redirects > MAX_REDIRECTS) {
                            throw DownloadException.Policy("Too many redirects")
                        }
                        val location = connection.getHeaderField("Location")
                            ?: throw DownloadException.Http(status)
                        // Resolve relative redirects against the current URL.
                        currentUrl = URI(currentUrl).resolve(location).toString()
                        // Loop re-validates the new target via the policy gate.
                        connection.disconnect()
                        connection = null
                    }
                    else -> throw DownloadException.Http(status)
                }
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        } catch (e: CancellationException) {
            throw e
        } catch (e: DownloadException) {
            throw e
        } catch (e: IOException) {
            // Distinguish cancellation-abort from genuine network failure.
            if (coroutineContext.isActive.not()) throw CancellationException("download cancelled")
            throw DownloadException.Network(e)
        } catch (e: Exception) {
            throw DownloadException.Io(e)
        } finally {
            connection?.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection {
        val parsed = URL(url)
        val conn = parsed.openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.instanceFollowRedirects = false // redirects handled + validated here
        conn.setRequestProperty("User-Agent", "NarcicTub/0.1.0")
        return conn
    }

    private suspend fun copyBody(
        connection: HttpURLConnection,
        destination: File,
        onProgress: (DownloadProgress) -> Unit,
    ): DownloadFileResult {
        val totalBytes = connection.contentLengthLong.let { if (it > 0) it else null }
        val contentType = connection.contentType

        var written = 0L
        connection.inputStream.use { input ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                var sinceProgress = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    written += read
                    sinceProgress += read
                    if (sinceProgress >= PROGRESS_EVERY_BYTES) {
                        sinceProgress = 0
                        onProgress(DownloadProgress(downloadedBytes = written, totalBytes = totalBytes))
                        // Cancellation check inside the hot loop — aborts a
                        // long transfer promptly without a separate watcher.
                        coroutineContext.ensureActive()
                    }
                }
                output.flush()
            }
        }
        // Final real progress event so the last chunk is never lost.
        onProgress(DownloadProgress(downloadedBytes = written, totalBytes = totalBytes))
        if (written == 0L) throw DownloadException.Io(IOException("empty response body"))
        return DownloadFileResult(bytesDownloaded = written, contentType = contentType)
    }

    companion object {
        private const val BUFFER_SIZE = 16 * 1024
        private const val PROGRESS_EVERY_BYTES = 64 * 1024
        private const val MAX_REDIRECTS = 5
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
    }
}
