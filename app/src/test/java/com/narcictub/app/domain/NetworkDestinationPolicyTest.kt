package com.narcictub.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/**
 * NetworkDestinationPolicy tests — the SSRF boundary introduced with the
 * Phase 6 downloader. PUBLIC hosts must pass; loopback/private/link-local/
 * metadata targets must be rejected.
 *
 * M-1 (Phase 6 security review) coverage: stage 2 resolves the host and
 * rejects when ANY resolved address is private — not only the first — and
 * the production downloader runs BOTH stages before EVERY hop.
 */
class NetworkDestinationPolicyTest {

    // ===== allowed =====

    @Test
    fun `public https url is allowed`() {
        assertTrue(NetworkDestinationPolicy.isAllowed("https://example.com/file.mp4"))
        assertNull(NetworkDestinationPolicy.disallowedReason("https://cdn.example.org/a.mp4"))
    }

    @Test
    fun `public http url is allowed`() {
        assertTrue(NetworkDestinationPolicy.isAllowed("http://example.com/file.mp4"))
    }

    @Test
    fun `public ipv4 literal is allowed`() {
        assertTrue(NetworkDestinationPolicy.isAllowed("http://93.184.216.34/file"))
    }

    @Test
    fun `public ipv6 literal is allowed`() {
        assertTrue(NetworkDestinationPolicy.isAllowed("http://[2606:2800:220:1:248:1893:25c8:1946]/file"))
    }

    @Test
    fun `uppercase scheme is allowed`() {
        assertTrue(NetworkDestinationPolicy.isAllowed("HTTPS://EXAMPLE.COM/X"))
    }

    // ===== loopback / local =====

    @Test
    fun `localhost is rejected`() {
        assertFalse(NetworkDestinationPolicy.isAllowed("http://localhost/file"))
    }

    @Test
    fun `127 loopback is rejected`() {
        assertFalse(NetworkDestinationPolicy.isAllowed("http://127.0.0.1/file"))
        assertFalse(NetworkDestinationPolicy.isAllowed("http://127.1.2.3/file"))
    }

    @Test
    fun `ipv6 loopback is rejected`() {
        assertFalse(NetworkDestinationPolicy.isAllowed("http://[::1]/file"))
        assertFalse(NetworkDestinationPolicy.isAllowed("http://[0:0:0:0:0:0:0:1]/file"))
    }

    @Test
    fun `any-local 0-0-0-0 is rejected`() {
        assertFalse(NetworkDestinationPolicy.isAllowed("http://0.0.0.0/file"))
    }

    // ===== private ranges =====

    @Test
    fun `private ipv4 ranges are rejected`() {
        assertFalse(NetworkDestinationPolicy.isAllowed("http://10.0.0.1/file"))
        assertFalse(NetworkDestinationPolicy.isAllowed("http://172.16.0.1/file"))
        assertFalse(NetworkDestinationPolicy.isAllowed("http://172.31.255.255/file"))
        assertFalse(NetworkDestinationPolicy.isAllowed("http://192.168.1.1/file"))
        assertFalse(NetworkDestinationPolicy.isAllowed("http://192.168.0.0/file"))
    }

    @Test
    fun `ipv6 unique-local is rejected`() {
        assertFalse(NetworkDestinationPolicy.isAllowed("http://[fd00::1]/file"))
        assertFalse(NetworkDestinationPolicy.isAllowed("http://[fc12::ab]/file"))
    }

    @Test
    fun `ipv6 link-local is rejected`() {
        assertFalse(NetworkDestinationPolicy.isAllowed("http://[fe80::1]/file"))
    }

    @Test
    fun `ipv4 link-local is rejected`() {
        assertFalse(NetworkDestinationPolicy.isAllowed("http://169.254.1.1/file"))
    }

    @Test
    fun `carrier-grade NAT range is rejected`() {
        // RFC 6598 100.64.0.0/10 — includes the Alibaba metadata endpoint.
        assertFalse(NetworkDestinationPolicy.isAllowed("http://100.100.100.200/latest/meta-data/"))
        assertFalse(NetworkDestinationPolicy.isAllowed("http://100.64.0.1/file"))
        assertFalse(NetworkDestinationPolicy.isAllowed("http://100.127.255.255/file"))
        // Public neighbors outside /10 stay allowed.
        assertTrue(NetworkDestinationPolicy.isAllowed("http://100.63.255.255/file"))
        assertTrue(NetworkDestinationPolicy.isAllowed("http://100.128.0.1/file"))
        assertTrue(NetworkDestinationPolicy.isAllowed("http://101.1.1.1/file"))
    }

    // ===== metadata services =====

    @Test
    fun `cloud metadata endpoints are rejected`() {
        assertFalse(NetworkDestinationPolicy.isAllowed("http://169.254.169.254/latest/meta-data/"))
        assertFalse(NetworkDestinationPolicy.isAllowed("http://metadata.google.internal/computeMetadata/"))
        assertFalse(NetworkDestinationPolicy.isAllowed("http://metadata.goog/computeMetadata/"))
    }

    // ===== scheme / credential / form attacks =====

    @Test
    fun `non-http schemes are rejected`() {
        assertFalse(NetworkDestinationPolicy.isAllowed("ftp://example.com/f"))
        assertFalse(NetworkDestinationPolicy.isAllowed("file:///etc/passwd"))
        assertFalse(NetworkDestinationPolicy.isAllowed("content://media/external/video/1"))
        assertFalse(NetworkDestinationPolicy.isAllowed("javascript:alert(1)"))
        assertFalse(NetworkDestinationPolicy.isAllowed("data:text/plain,hi"))
        assertFalse(NetworkDestinationPolicy.isAllowed("blob:https://example.com/uuid"))
        assertFalse(NetworkDestinationPolicy.isAllowed("intent://x/#Intent;scheme=https;end"))
    }

    @Test
    fun `embedded credentials are rejected`() {
        assertFalse(NetworkDestinationPolicy.isAllowed("https://user:pass@example.com/f"))
        assertFalse(NetworkDestinationPolicy.isAllowed("http://alice@example.com/f"))
        assertNotNull(NetworkDestinationPolicy.disallowedReason("https://u:p@93.184.216.34/f"))
    }

    @Test
    fun `malformed urls are rejected`() {
        assertFalse(NetworkDestinationPolicy.isAllowed("not a url"))
        assertFalse(NetworkDestinationPolicy.isAllowed("http://"))
        assertFalse(NetworkDestinationPolicy.isAllowed("https:///path"))
    }

    @Test
    fun `oversized host is rejected`() {
        val longHost = "a".repeat(300) + ".com"
        assertFalse(NetworkDestinationPolicy.isAllowed("http://$longHost/x"))
        assertEquals("host too long", NetworkDestinationPolicy.disallowedReason("http://$longHost/x"))
    }

    // ===== M-1: stage-2 post-DNS checking =====

    /** Resolves [text] via the JDK so tests assert real parsing semantics. */
    private fun addr(text: String): InetAddress = InetAddress.getByName(text)

    @Test
    fun `hostname resolving to any private address is rejected`() = kotlinx.coroutines.runBlocking {
        // M-1 core: a host with a PUBLIC first record and a PRIVATE second
        // record must be REJECTED — checking only the first address is the
        // hole this closes.
        val mixed = listOf(addr("93.184.216.34"), addr("10.0.0.7"))
        val reason = NetworkDestinationPolicy.disallowedReasonAfterDns("evil.example.com") { mixed }
        assertEquals("private or local addresses are not allowed", reason)

        val reversed = listOf(addr("192.168.1.5"), addr("93.184.216.34"))
        assertNotNull(NetworkDestinationPolicy.disallowedReasonAfterDns("evil.example.com") { reversed })
    }

    @Test
    fun `hostname resolving to all public addresses is allowed`() = kotlinx.coroutines.runBlocking {
        val public = listOf(addr("93.184.216.34"), addr("2606:2800:220:1:248:1893:25c8:1946"))
        assertNull(NetworkDestinationPolicy.disallowedReasonAfterDns("ok.example.com") { public })
    }

    @Test
    fun `each blocked category is rejected via resolution`() = kotlinx.coroutines.runBlocking {
        val hosts = mapOf(
            "a.example" to listOf(addr("127.0.0.1")),
            "b.example" to listOf(addr("169.254.169.254")),
            "c.example" to listOf(addr("100.100.100.200")),
            "d.example" to listOf(addr("fe80::1")),
            "e.example" to listOf(addr("fd00::1")),
            "f.example" to listOf(addr("::ffff:10.0.0.1")),
            "g.example" to listOf(addr("::10.0.0.1")),        // IPv4-compatible form
            "h.example" to listOf(addr("::127.0.0.1")),       // IPv4-compatible loopback
            "i.example" to listOf(addr("0.0.0.0")),
            "j.example" to listOf(addr("224.0.0.1")),          // multicast
        )
        for ((host, addresses) in hosts) {
            assertNotNull(
                "resolution containing ${addresses[0].hostAddress} must be blocked",
                NetworkDestinationPolicy.disallowedReasonAfterDns(host) { addresses },
            )
        }
    }

    @Test
    fun `resolver failure is rejected not allowed`() = kotlinx.coroutines.runBlocking {
        val reason = NetworkDestinationPolicy.disallowedReasonAfterDns("nx.example.com") {
            throw java.net.UnknownHostException("nx")
        }
        assertEquals("host could not be resolved", reason)
    }

    @Test
    fun `empty resolution is rejected`() = kotlinx.coroutines.runBlocking {
        assertEquals(
            "host could not be resolved",
            NetworkDestinationPolicy.disallowedReasonAfterDns("empty.example.com") { emptyList() },
        )
    }

    @Test
    fun `literal addresses skip the resolver entirely`() = kotlinx.coroutines.runBlocking {
        // Stage 1 already validated the literal; stage 2 must not re-resolve
        // (would be a second DNS round-trip on the downloader hot path).
        var resolverCalls = 0
        assertNull(
            NetworkDestinationPolicy.disallowedReasonAfterDns("93.184.216.34") { resolverCalls++; emptyList() },
        )
        assertEquals(0, resolverCalls)
    }

    @Test
    fun `stage one never resolves hostnames`() {
        // Stage 1 is a pure string check — a hostname that does not exist
        // must still pass stage 1 (its resolution happens in stage 2).
        assertNull(NetworkDestinationPolicy.disallowedReason("http://definitely-not-a-real-host.invalid/x"))
    }

    @Test
    fun `metadata hostnames are rejected after dns too`() = kotlinx.coroutines.runBlocking {
        assertNotNull(
            NetworkDestinationPolicy.disallowedReasonAfterDns("metadata.google.internal") { emptyList() },
        )
    }
}
