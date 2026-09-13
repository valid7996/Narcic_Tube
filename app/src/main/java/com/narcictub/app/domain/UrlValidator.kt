package com.narcictub.app.domain

import java.net.URI

/**
 * Pure URL validation for the Home input. Accepts only http/https URLs with
 * a host — everything else is rejected with a user-facing message.
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
        return null
    }

    /** Trims surrounding whitespace; leaves the URL otherwise untouched. */
    fun normalize(raw: String): String = raw.trim()
}
