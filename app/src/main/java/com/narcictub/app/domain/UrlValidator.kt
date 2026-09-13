package com.narcictub.app.domain

import java.net.URI

/**
 * Pure URL validation for the Home input. Accepts only http/https URLs with
 * a host — everything else is rejected with a user-facing message.
 *
 * SECURITY POLICY (documented, pinned by tests): URLs containing RFC 3986
 * userinfo (user:password@host) are REJECTED outright. Embedded credentials
 * must never flow into the resolver, logs, or persistence. Phase 4 review
 * finding S1 addressed here.
 */
object UrlValidator {

    fun isValidHttpUrl(raw: String): Boolean = validationMessage(raw.trim()) == null

    /** Null when valid, otherwise a short message for the text field. */
    fun validationMessage(raw: String): String? {
        val url = raw.trim()
        if (url.isEmpty()) return "Paste a link to begin"
        val uri = try {
            URI(url)
        } catch (_: Exception) {
            return "That doesn't look like a valid link"
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return "Link must start with http:// or https://"
        }
        val host = uri.host
        if (host.isNullOrBlank()) {
            return "That doesn't look like a valid link"
        }
        if (!uri.userInfo.isNullOrEmpty()) {
            return "Links with embedded usernames or passwords are not allowed"
        }
        return null
    }

    /** Trims surrounding whitespace; leaves the URL otherwise untouched. */
    fun normalize(raw: String): String = raw.trim()
}

