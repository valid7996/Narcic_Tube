package com.narcictub.app.data.downloader

import com.narcictub.app.domain.downloader.DownloadException
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * M-1 production-path regression tests (Phase 6 security review): the
 * downloader checks the destination before EVERY connection — original URL
 * AND each redirect hop — with BOTH policy stages (string check + resolve-
 * all-addresses check). Pinned with scripted [HttpURLConnection]s, so no
 * real network and no real DNS are involved.
 *
 * The stage-1/loopback behavior against a live server is additionally
 * pinned in HttpUrlConnectionDownloaderTest (real sockets, gate before any
 * connection exists).
 */
class HttpUrlConnectionDownloaderPolicyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** One scripted response for a URL hop. */
    private class Hop(
        val status: Int,
        val location: String? = null,
        val body: ByteArray = "data".toByteArray(),
    )

    /** Fake HttpURLConnection: no sockets, fully scripted responses. */
    private class ScriptedConnection(
        private val hop: Hop,
    ) : HttpURLConnection(URL("http://scripted.invalid/")) {
        private var connected = false

        override fun connect() { connected = true }
        override fun disconnect() { connected = false }
        override fun usingProxy(): Boolean = false

        override fun getResponseCode(): Int {
            connected = true
            return hop.status
        }

        override fun getHeaderField(name: String?): String? =
            if (name.equals("Location", ignoreCase = true)) hop.location else null

        override fun getInputStream(): InputStream {
            connected = true
            return ByteArrayInputStream(hop.body)
        }
    }

    /** Downloader whose connections are scripted per-URL; records opens. */
    private open class ScriptedDownloader(
        private val hops: Map<String, Hop>,
        private val openedUrls: MutableList<String>,
    ) : HttpUrlConnectionDownloader() {
        override val dnsResolve: (String) -> List<InetAddress> = { _ ->
            // Public resolution for any scripted host — the policy decision
            // under test is made from THESE addresses.
            listOf(InetAddress.getByName("93.184.216.34"))
        }

        override fun open(url: String): HttpURLConnection {
            openedUrls.add(url)
            return ScriptedConnection(hops[url] ?: error("no hop scripted for $url"))
        }
    }

    private fun destination(): File = File(tmp.newFolder(), "out.part")

    private fun assertPolicyBlocked(result: Result<*>): String {
        val e = result.exceptionOrNull()
        assertTrue("expected DownloadException.Policy, got $e", e is DownloadException.Policy)
        return (e as DownloadException.Policy).message ?: ""
    }

    @Test
    fun `redirect chain is followed and every hop is gate checked`() {
        val hops = mapOf(
            "https://cdn.example.com/first" to Hop(302, location = "https://cdn.example.com/second"),
            "https://cdn.example.com/second" to Hop(302, location = "https://cdn.example.org/hop3"),
            "https://cdn.example.org/hop3" to Hop(200, body = "done".toByteArray()),
        )
        val opened = mutableListOf<String>()
        val downloader = ScriptedDownloader(hops, opened)

        val result = runCatching {
            runBlocking { downloader.download("https://cdn.example.com/first", destination()) {} }
        }
        result.exceptionOrNull()?.let { fail("public redirect chain must complete: $it") }

        // Every hop — original and both redirects — passed the gate and was
        // opened, in order.
        assertEquals(
            listOf(
                "https://cdn.example.com/first",
                "https://cdn.example.com/second",
                "https://cdn.example.org/hop3",
            ),
            opened,
        )
    }

    @Test
    fun `redirect to a private resolving host is blocked before connecting`() {
        val hops = mapOf(
            "https://cdn.example.com/first" to Hop(302, location = "https://internal.example.com/priv"),
        )
        val opened = mutableListOf<String>()
        val downloader = object : HttpUrlConnectionDownloader() {
            // First host resolves public, redirect host resolves PUBLIC+PRIVATE.
            override val dnsResolve: (String) -> List<InetAddress> = { host ->
                if (host.contains("internal")) {
                    listOf(InetAddress.getByName("93.184.216.34"), InetAddress.getByName("10.0.0.7"))
                } else {
                    listOf(InetAddress.getByName("93.184.216.34"))
                }
            }

            override fun open(url: String): HttpURLConnection {
                opened.add(url)
                return ScriptedConnection(hops[url] ?: error("no hop scripted for $url"))
            }
        }

        val result = runCatching {
            runBlocking { downloader.download("https://cdn.example.com/first", destination()) {} }
        }

        val message = assertPolicyBlocked(result)
        assertTrue(message.contains("private"))
        // The redirect target was never opened: blocked at the gate.
        assertEquals(listOf("https://cdn.example.com/first"), opened)
    }

    @Test
    fun `original url resolving to private in any record is blocked`() {
        val opened = mutableListOf<String>()
        val downloader = object : HttpUrlConnectionDownloader() {
            override val dnsResolve: (String) -> List<InetAddress> = { _ ->
                listOf(InetAddress.getByName("93.184.216.34"), InetAddress.getByName("192.168.0.9"))
            }

            override fun open(url: String): HttpURLConnection {
                opened.add(url)
                return ScriptedConnection(Hop(200))
            }
        }

        val result = runCatching {
            runBlocking { downloader.download("https://evil.example.com/x", destination()) {} }
        }

        assertTrue(result.exceptionOrNull() is DownloadException.Policy)
        assertEquals("blocked before any connection", emptyList<String>(), opened)
    }

    @Test
    fun `loopback resolving redirect hop is blocked`() {
        val hops = mapOf(
            "https://a.example.com/" to Hop(302, location = "http://b.example.com/"),
        )
        val opened = mutableListOf<String>()
        val downloader = object : HttpUrlConnectionDownloader() {
            override val dnsResolve: (String) -> List<InetAddress> = { host ->
                if (host.startsWith("b.")) {
                    listOf(InetAddress.getByName("127.0.0.1"))
                } else {
                    listOf(InetAddress.getByName("93.184.216.34"))
                }
            }

            override fun open(url: String): HttpURLConnection {
                opened.add(url)
                return ScriptedConnection(hops[url] ?: error("no hop scripted for $url"))
            }
        }

        val result = runCatching {
            runBlocking { downloader.download("https://a.example.com/", destination()) {} }
        }

        assertTrue(result.exceptionOrNull() is DownloadException.Policy)
        assertEquals(listOf("https://a.example.com/"), opened)
    }

    @Test
    fun `resolver failure blocks the download`() {
        val downloader = object : HttpUrlConnectionDownloader() {
            override val dnsResolve: (String) -> List<InetAddress> =
                { _ -> throw java.net.UnknownHostException("nx") }

            override fun open(url: String): HttpURLConnection = ScriptedConnection(Hop(200))
        }

        val result = runCatching {
            runBlocking { downloader.download("https://nx.example.com/f", destination()) {} }
        }

        val message = assertPolicyBlocked(result)
        assertTrue(message.contains("resolved"))
    }

    @Test
    fun `multi record resolution with all public addresses passes`() {
        val hops = mapOf(
            "https://cdn.example.com/a" to Hop(302, location = "https://cdn.example.com/b"),
            "https://cdn.example.com/b" to Hop(200, body = "done".toByteArray()),
        )
        val opened = mutableListOf<String>()
        val downloader = ScriptedDownloader(hops, opened)

        val result = runCatching {
            runBlocking { downloader.download("https://cdn.example.com/a", destination()) {} }
        }
        result.exceptionOrNull()?.let { fail("public multi-record resolution must pass: $it") }
        assertEquals(2, opened.size)
    }

    @Test
    fun `stage one blocks loopback literal without resolver or socket`() {
        val opened = mutableListOf<String>()
        var resolverCalls = 0
        val downloader = object : HttpUrlConnectionDownloader() {
            override val dnsResolve: (String) -> List<InetAddress> = { _ ->
                resolverCalls++
                emptyList()
            }

            override fun open(url: String): HttpURLConnection {
                opened.add(url)
                return ScriptedConnection(Hop(200))
            }
        }
        val result = runCatching {
            runBlocking { downloader.download("http://127.0.0.1/x", destination()) {} }
        }
        assertTrue(result.exceptionOrNull() is DownloadException.Policy)
        assertEquals(emptyList<String>(), opened)
        assertEquals(0, resolverCalls)
    }
}
