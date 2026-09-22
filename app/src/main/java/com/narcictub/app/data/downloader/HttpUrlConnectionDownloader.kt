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
 * Security (M-1): every URL — including every redirect target — passes
 * BOTH policy stages immediately before that hop's connection is opened:
 *  1. NetworkDestinationPolicy.disallowedReason — string-level (scheme,
 *     credentials, host form, literal addresses)
 *  2. NetworkDestinationPolicy.disallowedReasonAfterDns — resolves the
 *     host and rejects when ANY resolved address is private/local/
 *     metadata (all addresses, not only the first)
 * Nothing about the request (URL, headers, body) is ever logged.
 *
 * Known limitation (documented in NetworkDestinationPolicy): DNS
 * rebinding between stage 2 and the socket connect — HttpURLConnection
 * re-resolves at connect time; closing that requires socket pinning,
 * which the review agreed to keep as a documented limitation.
 */
@Singleton
open class HttpUrlConnectionDownloader @Inject constructor() : FileDownloader {

    /**
     * Address resolver used by the stage-2 policy check. Open seam so tests
     * can pin the all-addresses rule with crafted resolutions; production
     * uses real DNS via the policy default.
     */
    protected open val dnsResolve: (String) -> List<java.net.InetAddress> =
        NetworkDestinationPolicy::resolveAll

    override suspend fun download(
        url: String,
        destination: File,
        resumeFromBytes: Long,
        onProgress: (DownloadProgress) -> Unit,
    ): DownloadFileResult = withContext(Dispatchers.IO) {
        var currentUrl = url
        var redirects = 0
        var connection: HttpURLConnection? = null
        // A prior partial file only survives a genuine resumeFromBytes > 0
        // AND actually existing on disk — mismatched/missing bytes (e.g. the
        // staging file was cleared) fall back to a full, correct restart.
        val resumeOffset = resumeFromBytes.takeIf { it > 0L && destination.length() == it } ?: 0L

        try {
            while (true) {
                // Policy gate BEFORE every connection attempt — original URL
                // and every redirect target alike (M-1: both stages, so a
                // hostname that resolves to any private address is blocked,
                // even when it also resolves to public ones).
                checkDestination(currentUrl)

                connection = open(currentUrl, resumeOffset)
                val status = connection.responseCode

                when {
                    // 206 = the server honored Range and is sending only the
                    // remainder — append. 200 despite asking for a Range =
                    // the server doesn't support resuming and is sending the
                    // WHOLE file again — must overwrite, or the file would
                    // be corrupted by a duplicated prefix.
                    status in 200..299 -> {
                        val append = resumeOffset > 0L && status == HttpURLConnection.HTTP_PARTIAL
                        val result = copyBody(
                            connection,
                            destination,
                            append = append,
                            alreadyOnDisk = if (append) resumeOffset else 0L,
                            onProgress = onProgress,
                        )
                        return@withContext result
                    }
                    // The server says there is nothing beyond what we asked
                    // for — the file on disk is already the complete file.
                    // java.net.HttpURLConnection has no named constant for 416.
                    status == HTTP_RANGE_NOT_SATISFIABLE && resumeOffset > 0L -> {
                        val size = destination.length()
                        onProgress(DownloadProgress(downloadedBytes = size, totalBytes = size))
                        return@withContext DownloadFileResult(bytesDownloaded = size, contentType = connection.contentType)
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

    /**
     * M-1: full destination check before EVERY connection (original URL and
     * each redirect hop). Stage 1 is string-level; stage 2 resolves the host
     * and rejects when ANY resolved address is private/local/metadata.
     * Overridable seam for tests.
     */
    protected open suspend fun checkDestination(url: String) {
        NetworkDestinationPolicy.disallowedReason(url)?.let { reason ->
            throw DownloadException.Policy("Destination blocked: $reason")
        }
        val host = try {
            URI(url).host?.lowercase()
        } catch (_: Exception) {
            null
        } ?: throw DownloadException.Policy("Destination blocked: missing host")
        NetworkDestinationPolicy.disallowedReasonAfterDns(host, resolve = dnsResolve)?.let { reason ->
            throw DownloadException.Policy("Destination blocked: $reason")
        }
    }

    /**
     * Opens the connection for [url]. Protected seam: production builds a
     * real HttpURLConnection; tests substitute scripted responses to prove
     * the policy gate runs before EVERY hop (M-1).
     */
    protected open fun open(url: String, resumeFromBytes: Long = 0L): HttpURLConnection {
        val parsed = URL(url)
        val conn = parsed.openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.instanceFollowRedirects = false // redirects handled + validated here
        conn.setRequestProperty("User-Agent", "NarcicTub/0.1.0")
        if (resumeFromBytes > 0L) conn.setRequestProperty("Range", "bytes=$resumeFromBytes-")
        return conn
    }

    /**
     * [append]/[alreadyOnDisk] carry the resume decision made by the caller:
     * a fresh download truncates and starts at 0 (unchanged prior
     * behavior); a resumed one appends and starts counting from the bytes
     * already on disk, so [onProgress] and the returned total always
     * reflect the file's real, full size.
     */
    private suspend fun copyBody(
        connection: HttpURLConnection,
        destination: File,
        append: Boolean,
        alreadyOnDisk: Long,
        onProgress: (DownloadProgress) -> Unit,
    ): DownloadFileResult {
        // On a 206 the server reports the length of the REMAINDER only;
        // the real total is what's already on disk plus that remainder.
        val remaining = connection.contentLengthLong.let { if (it > 0) it else null }
        val totalBytes = remaining?.let { alreadyOnDisk + it }
        val contentType = connection.contentType

        var written = alreadyOnDisk
        connection.inputStream.use { input ->
            FileOutputStream(destination, append).use { output ->
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
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416
    }
}
