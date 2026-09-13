package com.narcictub.app.domain

/**
 * Pure filename policy for download display names. User-controlled strings
 * (URL segments, server-declared names) must pass through here before ever
 * touching the filesystem or MediaStore.
 *
 * Rules:
 *  - only the LAST path segment is kept, so traversal sequences never survive
 *  - control characters and reserved filesystem characters are removed
 *  - leading dots are stripped (hidden/traversal leftovers)
 *  - never URL-decodes input (no accidental reintroduction of dangerous forms)
 *  - length capped at [MAX_LENGTH]
 */
object FileNameSanitizer {

    const val DEFAULT_NAME = "download"
    const val MAX_LENGTH = 120
    private const val FORBIDDEN = ":*?\"<>|"

    /** Sanitizes [raw]; blank or unusable input falls back to [fallback]. */
    fun sanitize(raw: String?, fallback: String = DEFAULT_NAME): String {
        if (raw.isNullOrBlank()) return fallback
        // Last segment only — kills every ../ ..\ traversal form.
        var name = raw.substringAfterLast('/').substringAfterLast('\\').trim()
        name = name.filter { char -> char.code >= 0x20 && FORBIDDEN.none { it == char } }
        name = name.trimStart('.')
        if (name.isEmpty() || name == "." || name == ".." || name.all { it == '.' }) return fallback
        return name.take(MAX_LENGTH)
    }

    /**
     * Extracts a sanitized file name from a URL's last path segment.
     * Uses rawPath (NOT the decoded path) so %2e%2e%2f never becomes ../.
     * Null when the URL has no meaningful segment. Never decodes.
     */
    fun fromUrl(url: String): String? {
        return try {
            val path = java.net.URI(url).rawPath ?: return null
            val segment = path.substringAfterLast('/').trim()
            if (segment.isEmpty()) return null
            val sanitized = sanitize(segment, fallback = "")
            sanitized.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }
}
