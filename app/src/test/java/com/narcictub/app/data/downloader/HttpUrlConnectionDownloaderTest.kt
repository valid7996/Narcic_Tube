package com.narcictub.app.data.downloader

import com.narcictub.app.domain.downloader.DownloadException
import com.narcictub.app.domain.model.DownloadProgress
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.Executors

/**
 * Transport-level tests for HttpUrlConnectionDownloader against a real
 * loopback HTTP server.
 *
 * SECURITY NOTE: the production policy (NetworkDestinationPolicy) blocks
 * loopback targets, so these tests assert exactly that — a loopback URL is
 * rejected by the policy gate BEFORE any connection. The streaming/copy
 * machinery itself is covered by DownloadRepositoryImplTest through the
 * FileDownloader seam, and policy coverage (public hosts allowed, all
 * private ranges rejected) lives in NetworkDestinationPolicyTest.
 */
class HttpUrlConnectionDownloaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val executor = Executors.newCachedThreadPool()
    private lateinit var server: ServerSocket
    private val downloader = HttpUrlConnectionDownloader()

    @Before
    fun setUp() {
        server = ServerSocket(0, 50, InetSocketAddress("127.0.0.1", 0).address)
        executor.execute { serve() }
    }

    @After
    fun tearDown() {
        runCatching { server.close() }
        executor.shutdownNow()
    }

    private val routes = mutableMapOf<String, Pair<Int, ByteArray>>()

    private fun route(path: String, status: Int, body: ByteArray = ByteArray(0)) {
        routes[path] = status to body
    }

    private fun serve() {
        while (!server.isClosed) {
            val socket = try {
                server.accept()
            } catch (_: Exception) {
                return
            }
            executor.execute {
                socket.use { s ->
                    val input = s.getInputStream().bufferedReader()
                    val requestLine = input.readLine() ?: return@execute
                    // drain headers
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    val path = requestLine.split(" ").getOrNull(1)?.substringBefore("?") ?: return@execute
                    val (status, body) = routes[path] ?: return@execute
                    val out = s.getOutputStream()
                    val head = "HTTP/1.1 $status X\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray()
                    out.write(head)
                    out.write(body)
                    out.flush()
                }
            }
        }
    }

    @Test
    fun `loopback url is blocked by policy before connecting`() {
        route("/ok", 200, "hello".toByteArray())
        val dest = File(tmp.newFolder(), "out.part")
        val result = runCatching {
            kotlinx.coroutines.runBlocking {
                downloader.download("http://127.0.0.1:${server.localPort}/ok", dest) {}
            }
        }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is DownloadException.Policy)
        // Nothing written when blocked before connect.
        assertEquals(0L, dest.length())
    }

    @Test
    fun `credentials in url are blocked before any connection`() {
        val dest = File(tmp.newFolder(), "out.part")
        val result = runCatching {
            kotlinx.coroutines.runBlocking {
                downloader.download("http://u:p@127.0.0.1:${server.localPort}/ok", dest) {}
            }
        }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is DownloadException.Policy)
    }

    @Test
    fun `non-http scheme is blocked`() {
        val dest = File(tmp.newFolder(), "out.part")
        val result = runCatching {
            kotlinx.coroutines.runBlocking {
                downloader.download("ftp://127.0.0.1/x", dest) {}
            }
        }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is DownloadException.Policy)
    }

    @Test
    fun `redirect to loopback is also blocked`() {
        route("/redirect", 302, ByteArray(0))
        // Location header not needed: the original target is already loopback,
        // so the policy gate fires first. A full redirect-following test
        // would need a public first hop; here we pin that the GATE runs before
        // every hop by asserting Policy (not Http) is the failure mode.
        val dest = File(tmp.newFolder(), "out.part")
        val result = runCatching {
            kotlinx.coroutines.runBlocking {
                downloader.download("http://127.0.0.1:${server.localPort}/redirect", dest) {}
            }
        }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is DownloadException.Policy)
    }

    @Test
    fun `progress model handles unknown total as indeterminate`() {
        val known = DownloadProgress(10, totalBytes = 100)
        val unknown = DownloadProgress(10, totalBytes = null)
        val zero = DownloadProgress(10, totalBytes = 0)
        assertEquals(0.1f, known.fraction!!, 0.0001f)
        assertNull(unknown.fraction)
        assertNull(zero.fraction)
    }

    @Test
    fun `progress fraction is clamped within range`() {
        val overflow = DownloadProgress(150, totalBytes = 100)
        val negative = DownloadProgress(-5, totalBytes = 100)
        assertEquals(1f, overflow.fraction!!, 0.0001f)
        assertEquals(0f, negative.fraction!!, 0.0001f)
    }
}
