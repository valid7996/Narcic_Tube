package com.narcictub.app.data.resolver

import com.narcictub.app.domain.FileNameSanitizer
import com.narcictub.app.domain.NetworkDestinationPolicy
import com.narcictub.app.domain.model.MediaInfo
import com.narcictub.app.domain.model.MediaProvider
import com.narcictub.app.domain.model.MediaVariant
import com.narcictub.app.domain.resolver.MediaProviderDetector
import com.narcictub.app.domain.resolver.MediaResolveException
import com.narcictub.app.domain.resolver.MediaResolver
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Phase 8 REAL resolver for DIRECT media URLs — http/https links that serve
 * the media bytes themselves (e.g. https://host/clip.mp4). It extracts only
 * metadata that is genuinely present; nothing is ever fabricated.
 *
 * How it works (all on Dispatchers.IO):
 *  1. EVERY hop — original URL and each redirect target — passes BOTH
 *     NetworkDestinationPolicy stages (string check + resolve-all-addresses
 *     check), the identical gate to the Phase 6/7 downloader. No second,
 *     weaker HTTP path exists.
 *  2. Redirects are followed manually (max 5, [HttpURLConnection] with
 *     instanceFollowRedirects = false) so each target is validated BEFORE
 *     it is connected.
 *  3. The source is probed with HEAD. Servers that reject HEAD fall back to
 *     a bounded GET with "Range: bytes=0-4095": at most 4 KiB of the body is
 *     ever read for metadata — never the whole file.
 *  4. MIME type comes from the real response: declared Content-Type first;
 *     when that is missing or generic, [MediaMimeSniffer] identifies the
 *     container from the probed bytes. An HTML body (declared or sniffed)
 *     is an explicit UnsupportedSource — no platform extractor is faked.
 *  5. Size is trusted only from a valid Content-Length, or a Content-Range
 *     total on a 206 response; absent/invalid means null.
 *
 * Known limitation (inherited from the approved Phase 6 decision): DNS
 * rebinding between the stage-2 resolution and the socket connect is not
 * pinned; this transport re-resolves at connect time, same as the
 * downloader.
 */
@Singleton
open class DirectMediaResolver @Inject constructor() : MediaResolver {

    /**
     * Address resolver for the stage-2 policy check. Open seam so tests can
     * pin crafted resolutions; production uses real DNS via the policy
     * default (blocking — this class already runs on Dispatchers.IO).
     */
    protected open val dnsResolve: (String) -> List<InetAddress> =
        NetworkDestinationPolicy::resolveAll

    override suspend fun resolve(url: String): Result<MediaInfo> = withContext(Dispatchers.IO) {
        try {
            Result.success(doResolve(url))
        } catch (e: CancellationException) {
            throw e
        } catch (e: MediaResolveException) {
            Result.failure(e)
        } catch (e: IOException) {
            Result.failure(MediaResolveException.Network(e))
        } catch (e: Exception) {
            Result.failure(MediaResolveException.Internal(e))
        }
    }

    private suspend fun doResolve(url: String): MediaInfo {
        var probe = probe(url, method = "HEAD", bodyLimit = 0)
        var usedGetProbe = false

        // HEAD rejected (405/403/501/…) — retry as a bounded GET; servers
        // occasionally answer only the method that actually fetches bytes.
        if (probe.status in 400..599) {
            probe = probe(url, method = "GET", bodyLimit = PROBE_BODY_BYTES)
            usedGetProbe = true
        }
        if (probe.status !in 200..299) throw MediaResolveException.Http(probe.status)

        val declared = probe.contentType
        if (declared != null && isWebPageType(declared)) {
            throw MediaResolveException.UnsupportedSource()
        }

        // Bounded body probe whenever the declared type cannot establish a
        // media file: missing or generic types (and only then) are sniffed.
        if (!usedGetProbe && !isSpecificType(declared)) {
            probe = probe(probe.finalUrl, method = "GET", bodyLimit = PROBE_BODY_BYTES)
            usedGetProbe = true
            if (probe.status !in 200..299) throw MediaResolveException.Http(probe.status)
        }
        val sniffed = MediaMimeSniffer.sniff(probe.bodyPrefix)
        if (sniffed == "text/html") throw MediaResolveException.UnsupportedSource()

        val mimeType = when {
            !usedGetProbe && isSpecificType(declared) -> declared
            sniffed != null -> sniffed
            isSpecificType(declared) -> declared
            else -> null
        }

        // Size: only a valid, positive declaration counts. On a 206 the
        // Content-Length is the RANGE size — the total lives in Content-Range.
        val size = when {
            !usedGetProbe -> probe.contentLength
            probe.status == 206 -> probe.rangeTotal
            else -> probe.contentLength
        }

        // PHASE 18: real container metadata (duration/resolution/container)
        // for MP4-family direct files — bounded, policy-gated range reads.
        // Best effort: any failure leaves the fields honestly null.
        val sizeBytes = size?.takeIf { it > 0 }
        val containerMetadata: Mp4MetadataParser.Mp4Metadata? = try {
            if (isMp4Family(mimeType)) fetchMp4Metadata(probe.finalUrl, sizeBytes) else null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        val durationSeconds = containerMetadata?.durationSeconds
        val videoWidth = containerMetadata?.width
        val videoHeight = containerMetadata?.height
        val bitrateBps = if (sizeBytes != null && durationSeconds != null && durationSeconds > 0 &&
            // Phase 18 review LOW-1: sizeBytes * 8 must never overflow Long
            // (a hostile/absurd Content-Length near Long.MAX_VALUE would wrap
            // negative). Beyond the safe bound the bitrate is honestly null.
            sizeBytes <= Long.MAX_VALUE / 8
        ) {
            (sizeBytes * 8) / durationSeconds
        } else {
            null
        }
        // qualityLabel derived ONLY from the real decoded height — a real,
        // standard derivation, never a guess.
        val qualityLabel = videoHeight?.let { "${it}p" }

        return MediaInfo(
            sourceUrl = url,
            title = titleFrom(probe.contentDisposition, probe.finalUrl),
            // PHASE 17: host-based provider recognition only — recognition
            // does not imply extraction support.
            provider = MediaProviderDetector.detect(probe.finalUrl),
            // Host is guaranteed present by the policy gate; the coalesce
            // only guards a pathological parse failure — never a fallback
            // name is invented.
            host = hostOf(probe.finalUrl) ?: "",
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            durationSeconds = durationSeconds,
            qualityLabel = qualityLabel,
            videoWidth = videoWidth,
            videoHeight = videoHeight,
            bitrateBps = bitrateBps,
            variants = listOf(
                MediaVariant(
                    downloadUrl = probe.finalUrl,
                    mimeType = mimeType,
                    container = containerMetadata?.container,
                    width = videoWidth,
                    height = videoHeight,
                    sizeBytes = sizeBytes,
                    durationSeconds = durationSeconds,
                    qualityLabel = qualityLabel,
                ),
            ),
            isDirectFile = true,
            downloadUrl = probe.finalUrl,
        )
    }

    private fun isMp4Family(mimeType: String?): Boolean = mimeType in MP4_MIME_TYPES

    /**
     * PHASE 18: fetches bounded byte ranges (policy-gated like every other
     * request) and parses REAL MP4 container metadata. The moov atom is
     * looked up in a bounded prefix; when absent and the total size is
     * known, a bounded tail read covers files with a trailing moov.
     * Cancellation is always propagated; all other failures mean the
     * metadata stays null.
     */
    private suspend fun fetchMp4Metadata(
        url: String,
        totalSize: Long?,
    ): Mp4MetadataParser.Mp4Metadata? {
        coroutineContext.ensureActive()
        val prefix = fetchRange(url, 0, (METADATA_PREFIX_BYTES - 1).toLong())
            ?: return null
        var metadata = Mp4MetadataParser.parse(prefix)
        if (metadata?.durationSeconds == null &&
            totalSize != null && totalSize > METADATA_PREFIX_BYTES
        ) {
            coroutineContext.ensureActive()
            val tail = fetchRange(url, totalSize - METADATA_TAIL_BYTES, totalSize - 1)
            if (tail != null) {
                metadata = Mp4MetadataParser.parse(tail) ?: metadata
            }
        }
        return metadata
    }

    private suspend fun fetchRange(url: String, start: Long, endInclusive: Long): ByteArray? {
        coroutineContext.ensureActive()
        checkDestination(url) // same gate as every other request
        val connection = openRange(url, start, endInclusive)
        try {
            val status = connection.responseCode
            if (status !in 200..299) return null
            return readPrefix(connection.inputStream, METADATA_FETCH_CAP)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Single request with manual, policy-validated redirects. Returns the
     * terminal response's status/headers plus up to [bodyLimit] body bytes
     * (0 = headers only). Every failure maps to a [MediaResolveException].
     */
    private suspend fun probe(startUrl: String, method: String, bodyLimit: Int): ProbeResponse {
        var currentUrl = startUrl
        var redirects = 0
        while (true) {
            // Cancellation is observed before every blocking step.
            coroutineContext.ensureActive()
            checkDestination(currentUrl)

            val connection = open(currentUrl, method, bodyLimit)
            try {
                val status = connection.responseCode
                if (status in 300..399) {
                    redirects += 1
                    if (redirects > MAX_REDIRECTS) {
                        throw MediaResolveException.Policy("too many redirects")
                    }
                    val location = connection.getHeaderField("Location")
                        ?: throw MediaResolveException.Http(status)
                    // Relative targets resolve against the current hop; the
                    // loop re-runs BOTH policy stages on the new URL.
                    currentUrl = try {
                        URI(currentUrl).resolve(location).toString()
                    } catch (_: Exception) {
                        throw MediaResolveException.Http(status) // malformed Location
                    }
                    continue
                }

                val contentType = connection.contentType
                    ?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
                val contentLength = connection.contentLengthLong.takeIf { it > 0 }
                val rangeTotal = parseRangeTotal(connection.getHeaderField("Content-Range"))
                val disposition = connection.getHeaderField("Content-Disposition")
                val prefix = if (bodyLimit > 0 && status in 200..299) {
                    readPrefix(connection.inputStream, bodyLimit)
                } else {
                    ByteArray(0)
                }
                return ProbeResponse(
                    status = status,
                    finalUrl = currentUrl,
                    contentType = contentType,
                    contentLength = contentLength,
                    rangeTotal = rangeTotal,
                    contentDisposition = disposition,
                    bodyPrefix = prefix,
                )
            } finally {
                connection.disconnect()
            }
        }
    }

    /**
     * M-1 parity with the downloader: BOTH policy stages before EVERY
     * connection — string-level check, then resolve-the-host check that
     * rejects when ANY resolved address is private/local/metadata.
     */
    protected open suspend fun checkDestination(url: String) {
        NetworkDestinationPolicy.disallowedReason(url)?.let { reason ->
            throw MediaResolveException.Policy(reason)
        }
        val host = try {
            URI(url).host?.lowercase()
        } catch (_: Exception) {
            null
        } ?: throw MediaResolveException.Policy("missing host")
        NetworkDestinationPolicy.disallowedReasonAfterDns(host, resolve = dnsResolve)?.let { reason ->
            throw MediaResolveException.Policy(reason)
        }
    }

    /**
     * Opens the connection for [url]. Protected seam: production builds a
     * real HttpURLConnection; tests substitute scripted responses.
     */
    protected open fun open(url: String, method: String, bodyLimit: Int): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = false // redirects handled + validated here
        connection.setRequestProperty("User-Agent", "NarcicTub/0.1.0")
        if (method == "GET" && bodyLimit > 0) {
            connection.setRequestProperty("Range", "bytes=0-${bodyLimit - 1}")
        }
        return connection
    }

    /**
     * PHASE 18: range request for bounded container-metadata reads. Default
     * implementation reuses [open] (same timeouts, same redirect policy);
     * tests can override it independently of [open].
     */
    protected open fun openRange(url: String, start: Long, endInclusive: Long): HttpURLConnection =
        open(url, "GET", 0).apply {
            setRequestProperty("Range", "bytes=$start-$endInclusive")
        }

    /** Reads at most [limit] bytes, checking cancellation between chunks. */
    private suspend fun readPrefix(stream: InputStream, limit: Int): ByteArray {
        val buffer = ByteArray(limit)
        var read = 0
        while (read < limit) {
            coroutineContext.ensureActive()
            val n = stream.read(buffer, read, limit - read)
            if (n == -1) break
            read += n
        }
        return if (read == limit) buffer else buffer.copyOf(read)
    }

    private fun titleFrom(disposition: String?, finalUrl: String): String? {
        filenameFromDisposition(disposition)?.let { raw ->
            return FileNameSanitizer.sanitize(raw, fallback = "").takeIf { it.isNotEmpty() }
        }
        // Fall back to the URL's own file name (decoded for display, then
        // sanitized — the sanitizer kills any traversal a decode could
        // reintroduce). Null when neither source yields a usable name.
        return try {
            val raw = URI(finalUrl).rawPath?.substringAfterLast('/') ?: return null
            if (raw.isBlank()) return null
            val decoded = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrNull() ?: raw
            FileNameSanitizer.sanitize(decoded, fallback = "").takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    /** RFC 6266/5987 Content-Disposition filename extraction. */
    private fun filenameFromDisposition(header: String?): String? {
        if (header.isNullOrBlank()) return null
        // filename*=UTF-8''name (RFC 5987) takes precedence when present.
        Regex("""filename\*\s*=\s*(?:([^']*)')([^']*)'([^;]*)""", RegexOption.IGNORE_CASE)
            .find(header)?.let { match ->
                val charset = match.groupValues[1].ifBlank { "UTF-8" }
                val decoded = runCatching {
                    URLDecoder.decode(match.groupValues[3], charset)
                }.getOrNull()
                return decoded?.takeIf { it.isNotBlank() }
            }
        val match = Regex("""filename\s*=\s*(?:"([^"]*)"|([^;]+))""", RegexOption.IGNORE_CASE)
            .find(header) ?: return null
        val value = (match.groupValues[1].ifEmpty { match.groupValues[2] }).trim().trim('"')
        return value.takeIf { it.isNotBlank() }
    }

    /** Total size from "bytes 0-4095/12345"; null for "*" or malformed values. */
    private fun parseRangeTotal(header: String?): Long? {
        if (header.isNullOrBlank()) return null
        val total = header.substringAfterLast('/', "").trim()
        if (total.isEmpty() || total == "*") return null
        return total.toLongOrNull()?.takeIf { it > 0 }
    }

    private fun hostOf(url: String): String? = try {
        URI(url).host
    } catch (_: Exception) {
        null
    }

    private fun isWebPageType(type: String): Boolean =
        type == "text/html" || type == "application/xhtml+xml"

    /**
     * True when the server declared a concrete type that establishes what
     * the bytes are (any audio/ or video/, images, documents, archives…).
     * Generic containers ("octet-stream", "binary") and absent types force
     * the sniffing probe instead.
     */
    private fun isSpecificType(type: String?): Boolean {
        if (type == null) return false
        if (type in GENERIC_TYPES) return false
        return type.startsWith("audio/") ||
            type.startsWith("video/") ||
            type.startsWith("image/") ||
            type.startsWith("text/") ||
            type.startsWith("application/")
    }

    private data class ProbeResponse(
        val status: Int,
        val finalUrl: String,
        val contentType: String?,
        val contentLength: Long?,
        val rangeTotal: Long?,
        val contentDisposition: String?,
        val bodyPrefix: ByteArray,
    )

    companion object {
        private const val PROBE_BODY_BYTES = 4 * 1024
        private const val MAX_REDIRECTS = 5
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 10_000

        // PHASE 18: bounded metadata reads — a prefix for front-packed moov
        // atoms plus a tail read for files with a trailing moov. All reads
        // are capped; nothing is ever read unbounded.
        private const val METADATA_PREFIX_BYTES = 256 * 1024
        private const val METADATA_TAIL_BYTES = 64 * 1024
        private const val METADATA_FETCH_CAP = 256 * 1024

        private val MP4_MIME_TYPES =
            setOf("video/mp4", "audio/mp4", "audio/m4a", "audio/x-m4a")
        private val GENERIC_TYPES =
            setOf("application/octet-stream", "application/binary", "application/unknown")
    }
}
