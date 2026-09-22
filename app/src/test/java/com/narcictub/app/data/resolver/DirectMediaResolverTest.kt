package com.narcictub.app.data.resolver

import com.narcictub.app.domain.model.MediaInfo
import com.narcictub.app.domain.model.MediaProvider
import com.narcictub.app.domain.resolver.MediaResolveException
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 8 resolver tests against fully scripted [HttpURLConnection]s — no
 * real network, no real DNS. Pins: honest metadata extraction, the BOTH-
 * stages destination gate before EVERY hop, redirect validation, MIME
 * sniffing, Content-Length/Content-Range handling and cancellation.
 */
class DirectMediaResolverTest {

    /** One scripted response for a request. */
    private class Hop(
        val status: Int,
        val headers: Map<String, String> = emptyMap(),
        val body: ByteArray = ByteArray(0),
    )

    /** Fake HttpURLConnection: no sockets, fully scripted. */
    private class ScriptedConnection(private val hop: Hop) : HttpURLConnection(
        URL("http://scripted.invalid/"),
    ) {
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy(): Boolean = false
        override fun getResponseCode(): Int = hop.status
        override fun getHeaderField(name: String?): String? {
            val key = name?.lowercase() ?: return null
            return hop.headers[key]
        }

        override fun getInputStream(): InputStream {
            if (hop.status !in 200..299) throw IOException("no body for status ${hop.status}")
            return ByteArrayInputStream(hop.body)
        }
    }

    private open class ScriptedResolver(
        private val hops: Map<String, Hop>,
        private val dns: (String) -> List<InetAddress> = {
            listOf(InetAddress.getByName("93.184.216.34"))
        },
    ) : DirectMediaResolver() {
        val opened = mutableListOf<String>()
        override val dnsResolve: (String) -> List<InetAddress> = dns
        override fun open(url: String, method: String, bodyLimit: Int): HttpURLConnection {
            opened += "$method $url"
            return ScriptedConnection(hops["$method $url"] ?: error("no hop scripted for $method $url"))
        }
    }

    private fun mp4Prefix(bytes: Int = 2048): ByteArray {
        val data = ByteArray(bytes)
        "ftyp".forEachIndexed { i, c -> data[4 + i] = c.code.toByte() }
        "isom".forEachIndexed { i, c -> data[8 + i] = c.code.toByte() }
        return data
    }

    private fun mp3Prefix(): ByteArray {
        val data = ByteArray(1024)
        "ID3".forEachIndexed { i, c -> data[i] = c.code.toByte() }
        return data
    }

    @Test
    fun `direct media url resolves real metadata from headers`() = runBlocking {
        val resolver = ScriptedResolver(
            mapOf(
                "HEAD https://cdn.example.com/clip.mp4" to Hop(
                    status = 200,
                    headers = mapOf("content-type" to "video/mp4", "content-length" to "1048576"),
                ),
            ),
        )

        val result = resolver.resolve("https://cdn.example.com/clip.mp4")

        assertTrue(result.isSuccess)
        val info = result.getOrNull()!!
        assertEquals("video/mp4", info.mimeType)
        assertEquals(1048576L, info.sizeBytes)
        assertEquals("clip.mp4", info.title) // real URL file name, not invented
        assertEquals("cdn.example.com", info.host)
        // PHASE 17: host-based provider recognition — an unknown host stays unknown.
        assertEquals(MediaProvider.UNKNOWN, info.provider)
        assertTrue(info.isDirectFile)
        assertEquals("https://cdn.example.com/clip.mp4", info.downloadUrl)
        assertNull(info.qualityLabel)
        assertNull(info.durationSeconds)
        assertNull(info.thumbnailUrl)
    }

    @Test
    fun `content-disposition filename takes precedence as title`() = runBlocking {
        val resolver = ScriptedResolver(
            mapOf(
                "HEAD https://cdn.example.com/stream/123" to Hop(
                    status = 200,
                    headers = mapOf(
                        "content-type" to "audio/mpeg",
                        "content-length" to "3000",
                        "content-disposition" to "attachment; filename=\"My Clip.mp3\"",
                    ),
                ),
            ),
        )

        val info = resolver.resolve("https://cdn.example.com/stream/123").getOrNull()!!

        assertEquals("My Clip.mp3", info.title)
    }

    @Test
    fun `title is null when neither disposition nor url provides a name`() = runBlocking {
        val resolver = ScriptedResolver(
            mapOf(
                "HEAD https://cdn.example.com/" to Hop(
                    status = 200,
                    headers = mapOf("content-type" to "audio/mpeg"),
                ),
            ),
        )

        val info = resolver.resolve("https://cdn.example.com/").getOrNull()!!

        assertNull(info.title)
        assertNull(info.sizeBytes) // no Content-Length declared → unknown
    }

    @Test
    fun `head rejected with 405 falls back to bounded get probe`() = runBlocking {
        val resolver = ScriptedResolver(
            mapOf(
                "HEAD https://cdn.example.com/clip.mp4" to Hop(status = 405),
                "GET https://cdn.example.com/clip.mp4" to Hop(
                    status = 200,
                    headers = mapOf("content-type" to "video/mp4", "content-length" to "5000"),
                ),
            ),
        )

        val info = resolver.resolve("https://cdn.example.com/clip.mp4").getOrNull()!!

        assertEquals("video/mp4", info.mimeType)
        assertEquals(5000L, info.sizeBytes)
        // PHASE 18: the MP4 metadata probe follows the probe GET (bounded
        // range read); no other connection is made.
        assertEquals(
            listOf(
                "HEAD https://cdn.example.com/clip.mp4",
                "GET https://cdn.example.com/clip.mp4",
                "GET https://cdn.example.com/clip.mp4",
            ),
            resolver.opened,
        )
    }

    @Test
    fun `missing content type is determined by magic-byte sniffing`() = runBlocking {
        val resolver = ScriptedResolver(
            mapOf(
                "HEAD https://cdn.example.com/media" to Hop(status = 200),
                "GET https://cdn.example.com/media" to Hop(
                    status = 206,
                    headers = mapOf(
                        "content-range" to "bytes 0-4095/10485760",
                        "content-length" to "4096",
                    ),
                    body = mp4Prefix(),
                ),
            ),
        )

        val info = resolver.resolve("https://cdn.example.com/media").getOrNull()!!

        assertEquals("video/mp4", info.mimeType)
        // Total from Content-Range, NOT the 4096 range Content-Length.
        assertEquals(10485760L, info.sizeBytes)
    }

    @Test
    fun `generic octet-stream is upgraded by sniffing`() = runBlocking {
        val resolver = ScriptedResolver(
            mapOf(
                "HEAD https://cdn.example.com/track" to Hop(
                    status = 200,
                    headers = mapOf(
                        "content-type" to "application/octet-stream",
                        "content-length" to "3000",
                    ),
                ),
                "GET https://cdn.example.com/track" to Hop(
                    status = 206,
                    headers = mapOf("content-range" to "bytes 0-4095/3000"),
                    body = mp3Prefix(),
                ),
            ),
        )

        val info = resolver.resolve("https://cdn.example.com/track").getOrNull()!!

        assertEquals("audio/mpeg", info.mimeType)
        assertEquals(3000L, info.sizeBytes)
    }

    @Test
    fun `html response is an explicit unsupported result, never faked`() = runBlocking {
        val resolver = ScriptedResolver(
            mapOf(
                "HEAD https://youtube.com/watch" to Hop(
                    status = 200,
                    headers = mapOf("content-type" to "text/html; charset=utf-8"),
                ),
            ),
        )

        val result = resolver.resolve("https://youtube.com/watch")

        assertTrue("expected UnsupportedSource, got ${result.exceptionOrNull()}", result.exceptionOrNull() is MediaResolveException.UnsupportedSource)
        // No body probe happens for a declared page — one request total.
        assertEquals(listOf("HEAD https://youtube.com/watch"), resolver.opened)
    }

    @Test
    fun `html served as octet-stream is sniffed and reported unsupported`() = runBlocking {
        val resolver = ScriptedResolver(
            mapOf(
                "HEAD https://evil.example.com/page" to Hop(
                    status = 200,
                    headers = mapOf("content-type" to "application/octet-stream"),
                ),
                "GET https://evil.example.com/page" to Hop(
                    status = 200,
                    body = "<!DOCTYPE html><html><body>watch page</body></html>".toByteArray(),
                ),
            ),
        )

        val result = resolver.resolve("https://evil.example.com/page")

        assertTrue(result.exceptionOrNull() is MediaResolveException.UnsupportedSource)
    }

    @Test
    fun `unknown binary stays unknown instead of being guessed`() = runBlocking {
        val resolver = ScriptedResolver(
            mapOf(
                "HEAD https://cdn.example.com/blob" to Hop(
                    status = 200,
                    headers = mapOf("content-type" to "application/octet-stream"),
                ),
                "GET https://cdn.example.com/blob" to Hop(
                    status = 200,
                    headers = mapOf("content-length" to "123456"),
                    body = byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04),
                ),
            ),
        )

        val info = resolver.resolve("https://cdn.example.com/blob").getOrNull()!!

        assertNull(info.mimeType) // honest unknown
        assertEquals(123456L, info.sizeBytes)
        assertTrue(info.isDirectFile)
    }

    @Test
    fun `http failure surfaces as typed Http failure`() = runBlocking {
        val resolver = ScriptedResolver(
            mapOf(
                "HEAD https://cdn.example.com/gone.mp4" to Hop(status = 404),
                "GET https://cdn.example.com/gone.mp4" to Hop(status = 404),
            ),
        )

        val result = resolver.resolve("https://cdn.example.com/gone.mp4")

        val error = result.exceptionOrNull()
        assertTrue("expected Http, got $error", error is MediaResolveException.Http)
        assertEquals(404, (error as MediaResolveException.Http).statusCode)
    }

    @Test
    fun `network failure surfaces as typed Network failure`() = runBlocking {
        val resolver = object : ScriptedResolver(emptyMap()) {
            override fun open(url: String, method: String, bodyLimit: Int): HttpURLConnection =
                throw IOException("connection refused")
        }

        val result = resolver.resolve("https://unreachable.example.com/f.mp4")

        assertTrue(result.exceptionOrNull() is MediaResolveException.Network)
    }

    @Test
    fun `redirect chain is followed and every hop passes the gate`() = runBlocking {
        val resolver = ScriptedResolver(
            mapOf(
                "HEAD https://a.example.com/one" to Hop(302, headers = mapOf("location" to "https://b.example.com/two")),
                "HEAD https://b.example.com/two" to Hop(302, headers = mapOf("location" to "https://c.example.com/final.mp4")),
                "HEAD https://c.example.com/final.mp4" to Hop(
                    status = 200,
                    headers = mapOf("content-type" to "video/mp4", "content-length" to "77"),
                ),
            ),
        )

        val info = resolver.resolve("https://a.example.com/one").getOrNull()!!

        assertEquals("https://c.example.com/final.mp4", info.downloadUrl)
        assertEquals("final.mp4", info.title)
        // PHASE 18: 3 redirect hops + 1 bounded MP4 metadata request on the
        // final URL.
        assertEquals(4, resolver.opened.size)
    }

    @Test
    fun `relative redirect targets are resolved and validated`() = runBlocking {
        val resolver = ScriptedResolver(
            mapOf(
                "HEAD https://a.example.com/dir/clip.mp4" to Hop(302, headers = mapOf("location" to "../other/clip.mp4")),
                "HEAD https://a.example.com/other/clip.mp4" to Hop(
                    status = 200,
                    headers = mapOf("content-type" to "video/mp4", "content-length" to "9"),
                ),
            ),
        )

        val info = resolver.resolve("https://a.example.com/dir/clip.mp4").getOrNull()!!

        assertEquals("https://a.example.com/other/clip.mp4", info.downloadUrl)
    }

    @Test
    fun `redirect to a private-resolving host is blocked before connecting`() = runBlocking {
        val resolver = object : ScriptedResolver(
            mapOf(
                "HEAD https://a.example.com/one" to Hop(302, headers = mapOf("location" to "https://internal.example.com/priv")),
            ),
        ) {
            override val dnsResolve: (String) -> List<InetAddress> = { host ->
                if (host.startsWith("internal")) {
                    // Attacker multi-record: one public, one private — must block.
                    listOf(InetAddress.getByName("93.184.216.34"), InetAddress.getByName("10.0.0.7"))
                } else {
                    listOf(InetAddress.getByName("93.184.216.34"))
                }
            }
        }

        val result = resolver.resolve("https://a.example.com/one")

        val error = result.exceptionOrNull()
        assertTrue("expected Policy, got $error", error is MediaResolveException.Policy)
        assertTrue(error!!.message!!.contains("private"))
        // The redirect target was never opened: blocked at the gate.
        assertEquals(listOf("HEAD https://a.example.com/one"), resolver.opened)
    }

    @Test
    fun `original url resolving to any private record is blocked`() = runBlocking {
        val resolver = object : ScriptedResolver(emptyMap()) {
            override val dnsResolve: (String) -> List<InetAddress> = { _ ->
                listOf(InetAddress.getByName("93.184.216.34"), InetAddress.getByName("192.168.0.9"))
            }
        }

        val result = resolver.resolve("https://evil.example.com/x.mp4")

        assertTrue(result.exceptionOrNull() is MediaResolveException.Policy)
        assertEquals(emptyList<String>(), resolver.opened)
    }

    @Test
    fun `loopback literal is blocked by stage one with no resolver call or socket`() = runBlocking {
        var resolverCalls = 0
        val resolver = object : ScriptedResolver(emptyMap()) {
            override val dnsResolve: (String) -> List<InetAddress> = {
                resolverCalls++
                emptyList()
            }
        }

        val result = resolver.resolve("http://127.0.0.1/x.mp4")

        assertTrue(result.exceptionOrNull() is MediaResolveException.Policy)
        assertEquals(emptyList<String>(), resolver.opened)
        assertEquals(0, resolverCalls)
    }

    @Test
    fun `userinfo url is rejected by the destination policy`() = runBlocking {
        val resolver = ScriptedResolver(emptyMap())

        val result = resolver.resolve("https://user:pass@example.com/f.mp4")

        assertTrue(result.exceptionOrNull() is MediaResolveException.Policy)
        assertEquals(emptyList<String>(), resolver.opened)
    }

    @Test
    fun `dangerous scheme is rejected without any connection`() = runBlocking {
        val resolver = ScriptedResolver(emptyMap())

        val result = resolver.resolve("file:///etc/passwd")

        assertTrue(result.exceptionOrNull() is MediaResolveException.Policy)
        assertEquals(emptyList<String>(), resolver.opened)
    }

    @Test
    fun `too many redirects is a policy failure`() = runBlocking {
        val hops = mutableMapOf<String, Hop>()
        for (i in 0..5) {
            hops["HEAD https://loop.example.com/h$i"] =
                Hop(302, headers = mapOf("location" to "https://loop.example.com/h${i + 1}"))
        }
        val resolver = ScriptedResolver(hops)

        val result = resolver.resolve("https://loop.example.com/h0")

        assertTrue(result.exceptionOrNull() is MediaResolveException.Policy)
    }

    @Test
    fun `206 without a numeric content-range total reports unknown size`() = runBlocking {
        val resolver = ScriptedResolver(
            mapOf(
                "HEAD https://cdn.example.com/clip.mp4" to Hop(status = 405),
                "GET https://cdn.example.com/clip.mp4" to Hop(
                    status = 206,
                    headers = mapOf(
                        "content-range" to "bytes 0-4095/*",
                        "content-length" to "4096",
                    ),
                    body = mp4Prefix(),
                ),
            ),
        )

        val info = resolver.resolve("https://cdn.example.com/clip.mp4").getOrNull()!!

        assertNull(info.sizeBytes) // "*" total is unknown — never guessed
        assertEquals("video/mp4", info.mimeType)
    }

    @Test
    fun `malformed content-length is ignored and size stays unknown`() = runBlocking {
        val resolver = ScriptedResolver(
            mapOf(
                "HEAD https://cdn.example.com/clip.mp4" to Hop(
                    status = 200,
                    headers = mapOf("content-type" to "video/mp4", "content-length" to "not-a-number"),
                ),
            ),
        )

        val info = resolver.resolve("https://cdn.example.com/clip.mp4").getOrNull()!!

        assertNull(info.sizeBytes)
        assertEquals("video/mp4", info.mimeType)
    }

    @Test
    fun `cancellation aborts a mid-flight redirect chain`() {
        runBlocking {
            val hops = mapOf(
                "HEAD https://a.example.com/one" to Hop(302, headers = mapOf("location" to "https://b.example.com/two")),
                "HEAD https://b.example.com/two" to Hop(302, headers = mapOf("location" to "https://c.example.com/three")),
                "HEAD https://c.example.com/three" to Hop(302, headers = mapOf("location" to "https://d.example.com/four")),
                "HEAD https://d.example.com/four" to Hop(200, headers = mapOf("content-type" to "video/mp4")),
            )
            val parentJob = Job()
            val scope = CoroutineScope(parentJob + Dispatchers.Default)
            val resolver = object : ScriptedResolver(hops) {
                override fun open(url: String, method: String, bodyLimit: Int): HttpURLConnection {
                    // Cancel the caller while the third hop is being opened —
                    // the next loop iteration must observe it and stop.
                    if (opened.size == 2) parentJob.cancel()
                    return super.open(url, method, bodyLimit)
                }
            }
            var cancelled = false

            val task = scope.launch {
                try {
                    resolver.resolve("https://a.example.com/one")
                } catch (_: CancellationException) {
                    cancelled = true
                }
            }
            task.join()

            assertTrue("resolve must be aborted by cancellation", cancelled)
            // Chain stopped at the third hop; no fourth connection was opened.
            assertEquals(3, resolver.opened.size)
            parentJob.complete()
        }
    }

    @Test
    fun `resolve started in an already-cancelled scope never connects`() = runBlocking {
        val resolver = ScriptedResolver(emptyMap())
        val job = launch(start = CoroutineStart.LAZY) { resolver.resolve("https://cdn.example.com/x") }
        job.cancel()
        job.join()

        assertEquals(emptyList<String>(), resolver.opened)
    }

    // ===== Phase 18: real MP4 container metadata extraction =====

    /** Scripted resolver with independent, range-aware metadata responses. */
    private class RangeScriptedResolver(
        private val hops: Map<String, Hop>,
        private val rangeBodies: Map<Long, ByteArray>,
    ) : DirectMediaResolver() {
        val opened = mutableListOf<String>()
        val rangeRequests = mutableListOf<Pair<Long, Long>>()

        override val dnsResolve: (String) -> List<InetAddress> = {
            listOf(InetAddress.getByName("93.184.216.34"))
        }

        override fun open(url: String, method: String, bodyLimit: Int): HttpURLConnection {
            opened += "$method $url"
            return ScriptedConnection(hops["$method $url"] ?: error("no hop scripted for $method $url"))
        }

        override fun openRange(url: String, start: Long, endInclusive: Long): HttpURLConnection {
            rangeRequests += start to endInclusive
            val body = rangeBodies[start] ?: error("no range body scripted for start=$start")
            return ScriptedConnection(
                Hop(206, headers = mapOf("content-range" to "bytes $start-$endInclusive/1"), body = body),
            )
        }
    }

    @Test
    fun `mp4 container metadata is extracted through bounded range reads`() = runBlocking {
        val body = mp4Ftyp("isom") + mp4Moov(
            mp4MvhdV0(timescale = 1000, duration = 95_000),
            mp4Trak(mp4TkhdV0(width = 1920, height = 1080)),
        )
        val resolver = RangeScriptedResolver(
            hops = mapOf(
                "HEAD https://cdn.example.com/movie.mp4" to Hop(
                    status = 200,
                    headers = mapOf("content-type" to "video/mp4", "content-length" to "1048576"),
                ),
            ),
            rangeBodies = mapOf(0L to body),
        )

        val info = resolver.resolve("https://cdn.example.com/movie.mp4").getOrNull()!!

        // All REAL values parsed from the actual container bytes.
        assertEquals(95L, info.durationSeconds)
        assertEquals(1920, info.videoWidth)
        assertEquals(1080, info.videoHeight)
        assertEquals("1080p", info.qualityLabel) // derived from the real height
        assertEquals(1048576L * 8 / 95, info.bitrateBps) // real size ÷ real duration
        assertEquals("mp4", info.variants.single().container)
        assertEquals(95L, info.variants.single().durationSeconds)
        // Bounded: one prefix read only (moov was found up front).
        assertEquals(listOf(0L to 262143L), resolver.rangeRequests)
    }

    @Test
    fun `trailing moov is found through the bounded tail read`() = runBlocking {
        val tailBody = mp4Moov(mp4MvhdV0(timescale = 1000, duration = 3_600_000))
        val resolver = RangeScriptedResolver(
            hops = mapOf(
                "HEAD https://cdn.example.com/trailer.mp4" to Hop(
                    status = 200,
                    headers = mapOf("content-type" to "video/mp4", "content-length" to "300000"),
                ),
            ),
            rangeBodies = mapOf(
                0L to mp4Prefix(), // front-packed bytes without moov
                234_464L to tailBody, // 300000 - 65536
            ),
        )

        val info = resolver.resolve("https://cdn.example.com/trailer.mp4").getOrNull()!!

        assertEquals(3600L, info.durationSeconds)
        assertEquals(
            listOf(0L to 262143L, 234_464L to 299_999L),
            resolver.rangeRequests,
        )
    }

    @Test
    fun `metadata fetch failure keeps resolve successful with honest nulls`() = runBlocking {
        val resolver = RangeScriptedResolver(
            hops = mapOf(
                "HEAD https://cdn.example.com/clip.mp4" to Hop(
                    status = 200,
                    headers = mapOf("content-type" to "video/mp4", "content-length" to "4096"),
                ),
            ),
            rangeBodies = emptyMap(), // metadata request will fail to script
        )

        val info = resolver.resolve("https://cdn.example.com/clip.mp4").getOrNull()!!

        assertTrue(info.isDirectFile)
        assertNull(info.durationSeconds)
        assertNull(info.videoWidth)
        assertNull(info.videoHeight)
        assertNull(info.bitrateBps)
        assertTrue("no fabricated quality", info.qualityLabel == null)
    }

    @Test
    fun `absurd content-length near long max never overflows the bitrate`() = runBlocking {
        // Phase 18 review LOW-1: a hostile/absurd Content-Length of
        // Long.MAX_VALUE must not make (sizeBytes * 8) wrap negative during
        // the bitrate division. With the guard, bitrateBps is honestly null
        // while the real duration/resolution still resolve.
        val body = mp4Ftyp("isom") + mp4Moov(
            mp4MvhdV0(timescale = 1000, duration = 95_000),
            mp4Trak(mp4TkhdV0(width = 1920, height = 1080)),
        )
        val resolver = RangeScriptedResolver(
            hops = mapOf(
                "HEAD https://cdn.example.com/huge.mp4" to Hop(
                    status = 200,
                    headers = mapOf(
                        "content-type" to "video/mp4",
                        "content-length" to "9223372036854775807", // Long.MAX_VALUE
                    ),
                ),
            ),
            rangeBodies = mapOf(0L to body),
        )

        val info = resolver.resolve("https://cdn.example.com/huge.mp4").getOrNull()!!

        assertEquals(Long.MAX_VALUE, info.sizeBytes)
        assertEquals("duration still real", 95L, info.durationSeconds)
        assertEquals(1920, info.videoWidth)
        assertEquals(1080, info.videoHeight)
        assertNull("overflow-safe: bitrate is null, never negative", info.bitrateBps)
        assertEquals("quality still derived from the real height", "1080p", info.qualityLabel)
    }
}
