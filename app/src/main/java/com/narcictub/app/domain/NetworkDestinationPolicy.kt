package com.narcictub.app.domain

import java.net.InetAddress
import java.net.URI

/**
 * NETWORK DESTINATION POLICY — documented tradeoff (Phase 6).
 *
 * NarcicTub downloads user-initiated media from the PUBLIC internet only.
 * By policy the app never needs to fetch from the device itself or from a
 * private network, so loopback/private/link-local/metadata-service targets
 * are REJECTED. This is a client-side SSRF guard: the user pastes the URL,
 * but a hostile clipboard/app could otherwise make the app probe or pull
 * from internal services on the user's device/network.
 *
 * WHAT THIS IS NOT: it is not a full SSRF boundary against DNS rebinding.
 * Gap: the host is validated BEFORE connection; HttpURLConnection may
 * re-resolve DNS at connect time, so a rebinding attacker could pass
 * validation on a public IP and connect on a private one. Practical
 * mitigation here is that downloads are user-initiated and user-visible;
 * the connect-time re-check of EVERY resolved address (while streaming)
 * is deliberately deferred — it requires intercepting the socket layer.
 * Documented limitation, not a silent claim of safety.
 */
object NetworkDestinationPolicy {

    private const val MAX_HOST_LENGTH = 253

    /** True when [url] may be fetched: http/https + public destination. */
    fun isAllowed(url: String): Boolean = disallowedReason(url) == null

    /** Null when allowed; short reason otherwise. */
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
        when (host) {
            "localhost", "0.0.0.0", "127.127.127.127" -> return "localhost is not allowed"
            "metadata.google.internal", "169.254.169.254" -> return "metadata service is not allowed"
        }

        // Literal IPv4/IPv6 address check (no DNS involved for literals).
        val literalAddress = parseLiteralAddress(host) ?: return null // hostname: DNS-resolved below

        if (isDisallowedIp(literalAddress)) return "private or local addresses are not allowed"
        return null
    }

    /** Resolves the hostname and rejects private destinations (post-DNS). */
    suspend fun disallowedReasonAfterDns(host: String): String? {
        val lower = host.lowercase()
        when (lower) {
            "localhost", "0.0.0.0" -> return "localhost is not allowed"
            "metadata.google.internal" -> return "metadata service is not allowed"
        }
        val literal = parseLiteralAddress(lower)
        val addresses = if (literal != null) {
            listOf(literal)
        } else {
            try {
                InetAddress.getAllByName(host).toList()
            } catch (_: Exception) {
                return "host could not be resolved"
            }
        }
        return addresses.firstOrNull { isDisallowedIp(it) }?.let { "private or local addresses are not allowed" }
    }

    private fun parseLiteralAddress(host: String): InetAddress? = try {
        // Strip IPv6 brackets; InetAddress handles both literal forms.
        InetAddress.getByName(host.removeSurrounding("[", "]"))
    } catch (_: Exception) {
        null
    }

    private fun isDisallowedIp(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
        ) {
            return true
        }
        // IPv6 unique-local (fc00::/7) — not covered by the checks above.
        val bytes = address.address
        if (bytes.size == 16 && (bytes[0].toInt() and 0xFE) == 0xFC) return true
        return false
    }
}
