package com.narcictub.app.domain

import java.net.InetAddress
import java.net.URI

/**
 * NETWORK DESTINATION POLICY — client-side SSRF guard (Phase 6, hardened
 * per the Phase 6 security review, finding M-1).
 *
 * NarcicTub downloads user-initiated media from the PUBLIC internet only.
 * By policy the app never needs to fetch from the device itself or from a
 * private network, so loopback/private/link-local/metadata targets are
 * REJECTED. The user pastes the URL, but a hostile clipboard/app could
 * otherwise make the app probe or pull from internal services on the
 * user's device/network.
 *
 * TWO STAGES, BOTH REQUIRED ON THE PRODUCTION PATH (see
 * HttpUrlConnectionDownloader.checkDestination, which runs them before
 * EVERY connection — original URL and each redirect hop):
 *  1. [disallowedReason] — string-level: scheme, embedded credentials,
 *     host form and literal addresses. Pure: it NEVER resolves DNS.
 *  2. [disallowedReasonAfterDns] — resolves the host and rejects when ANY
 *     resolved address is private/local/metadata. ALL addresses are
 *     inspected, not just the first: a hostname resolving to
 *     (public, private) must be blocked — a first-address-only check is
 *     exactly the hole a multi-record attacker walks through.
 *
 * BLOCKED (both stages): localhost / 0.0.0.0, every loopback (IPv4+IPv6),
 * IPv4 private (site-local) ranges, IPv4+IPv6 link-local, IPv4 carrier-grade
 * NAT 100.64.0.0/10 (RFC 6598 — includes the 100.100.100.200 metadata
 * endpoint), IPv6 unique-local fc00::/7 (incl. fd00::/8), multicast, and
 * cloud metadata services (hostnames + their link-local IPs). IPv4-mapped
 * and IPv4-compatible IPv6 literals (::ffff:10.0.0.1 / ::127.0.0.1) are
 * reduced to their embedded IPv4 and validated.
 *
 * DOCUMENTED LIMITATION (kept per review decision M-1): DNS rebinding
 * between the stage-2 resolution and the actual socket connect. The
 * production transport (HttpURLConnection) re-resolves DNS at connect time
 * and this policy does not pin the socket to the validated addresses —
 * closing that gap requires a custom socket factory. Downloads are
 * user-initiated and user-visible, which limits the practical impact.
 */
object NetworkDestinationPolicy {

    private const val MAX_HOST_LENGTH = 253

    /** Cloud metadata service hostnames (their IPs are link-local, caught as addresses). */
    private val METADATA_HOSTS = setOf("metadata.google.internal", "metadata.goog")

    /** True when [url] may be fetched: http/https + public destination. */
    fun isAllowed(url: String): Boolean = disallowedReason(url) == null

    /**
     * Stage 1 — string-level check, no DNS. Null when allowed; a short
     * reason otherwise. For hostnames this stage alone is NOT sufficient;
     * the production caller must also run [disallowedReasonAfterDns].
     */
    fun disallowedReason(url: String): String? {
        val uri = try {
            URI(url)
        } catch (_: Exception) {
            return "malformed URL"
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return "only http and https are supported"
        if (!uri.userInfo.isNullOrEmpty()) return "embedded credentials are not allowed"

        val host = uri.host?.lowercase() ?: return "missing host"
        if (host.length > MAX_HOST_LENGTH) return "host too long"
        if (host == "localhost" || host == "0.0.0.0") return "localhost is not allowed"
        if (host in METADATA_HOSTS) return "metadata service is not allowed"

        // Literal IPv4/IPv6 address check (no DNS involved for literals).
        val literalAddress = parseLiteralAddress(host) ?: return null // hostname: stage 2 resolves

        if (isDisallowedIp(literalAddress)) return "private or local addresses are not allowed"
        return null
    }

    /**
     * Stage 2 — post-resolution check. Resolves [host] and rejects when
     * ANY resolved address is private/local/metadata (M-1: every address,
     * not only the first).
     *
     * [resolve] is the address resolver, injectable so tests can pin the
     * all-addresses rule against crafted multi-record resolutions without
     * touching real DNS. Production uses the default [resolveAll] (real
     * DNS, blocking — call from an IO context).
     */
    suspend fun disallowedReasonAfterDns(
        host: String,
        resolve: (String) -> List<InetAddress> = { resolveAll(it) },
    ): String? {
        val lower = host.lowercase()
        if (lower == "localhost" || lower == "0.0.0.0") return "localhost is not allowed"
        if (lower in METADATA_HOSTS) return "metadata service is not allowed"

        // Literal addresses skip DNS entirely — the resolver must not run.
        val literal = parseLiteralAddress(lower)
        val addresses = if (literal != null) {
            listOf(literal)
        } else {
            try {
                resolve(lower)
            } catch (_: Exception) {
                return "host could not be resolved"
            }
        }
        if (addresses.isEmpty()) return "host could not be resolved"
        // M-1: EVERY resolved address is inspected — a hostname with one
        // public and one private A/AAAA record must be rejected.
        return if (addresses.any { isDisallowedIp(it) }) {
            "private or local addresses are not allowed"
        } else {
            null
        }
    }

    /** Default resolver — real DNS. Blocking; call from an IO context. */
    fun resolveAll(host: String): List<InetAddress> = InetAddress.getAllByName(host).toList()

    // Literal-form detection must never hand a bare hostname to
    // InetAddress.getByName — it RESOLVES hostnames (a DNS lookup from the
    // "no DNS" stage-1 check and a double resolution on the downloader
    // path). Only strings that LOOK like address literals get through.
    private val IPV4_LITERAL = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
    private val IPV6_LITERAL = Regex("""^[0-9A-Fa-f:.]+$""")

    private fun parseLiteralAddress(host: String): InetAddress? {
        val bare = host.removeSurrounding("[", "]")
        val looksLiteral = IPV4_LITERAL.matches(bare) ||
            (bare.contains(':') && IPV6_LITERAL.matches(bare))
        if (!looksLiteral) return null
        return try {
            InetAddress.getByName(bare)
        } catch (_: Exception) {
            null
        }
    }

    private fun isDisallowedIp(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
        ) {
            return true
        }
        val bytes = address.address
        // IPv4 carrier-grade NAT 100.64.0.0/10 — non-public (RFC 6598) and
        // hosts the Alibaba metadata endpoint 100.100.100.200.
        if (bytes.size == 4 && bytes[0] == 100.toByte() && (bytes[1].toInt() and 0xC0) == 64) return true
        // IPv4-mapped (::ffff:a.b.c.d) and IPv4-compatible (::a.b.c.d)
        // literals: reduce to the embedded IPv4 and re-check — the JDK hands
        // back either form, and the v4 payload would otherwise slip past the
        // Inet6Address range checks above.
        if (bytes.size == 16 && isEmbeddedIpv4Form(bytes)) {
            val embedded = runCatching { InetAddress.getByAddress(bytes.copyOfRange(12, 16)) }.getOrNull()
            if (embedded != null) return isDisallowedIp(embedded)
        }
        // IPv6 unique-local (fc00::/7) — not covered by the checks above.
        if (bytes.size == 16 && (bytes[0].toInt() and 0xFE) == 0xFC) return true
        return false
    }

    /** 16-byte form whose tail 4 bytes carry an IPv4 address (::x or ::ffff:x). */
    private fun isEmbeddedIpv4Form(b: ByteArray): Boolean =
        (0..9).all { b[it] == 0.toByte() } &&
            ((b[10] == 0.toByte() && b[11] == 0.toByte()) || (b[10] == 0xFF.toByte() && b[11] == 0xFF.toByte()))
}
