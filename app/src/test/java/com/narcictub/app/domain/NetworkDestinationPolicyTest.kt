package com.narcictub.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NetworkDestinationPolicy tests — the SSRF boundary introduced with the
 * Phase 6 downloader. PUBLIC hosts must pass; loopback/private/link-local/
 * metadata targets must be rejected.
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

    // ===== metadata services =====

    @Test
    fun `cloud metadata endpoints are rejected`() {
        assertFalse(NetworkDestinationPolicy.isAllowed("http://169.254.169.254/latest/meta-data/"))
        assertFalse(NetworkDestinationPolicy.isAllowed("http://metadata.google.internal/computeMetadata/"))
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
}
