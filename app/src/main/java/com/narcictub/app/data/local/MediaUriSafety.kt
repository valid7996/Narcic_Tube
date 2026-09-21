package com.narcictub.app.data.local

import java.io.File
import java.net.URI

/**
 * PHASE 10 — URI safety policy for opening a completed download's published
 * file. Pure and unit-testable: it only classifies URI STRINGS; the actual
 * resolving (ContentResolver / filesystem) lives in [MediaFileChecker].
 *
 * What may be opened:
 *  - content:// URIs with a non-blank authority and no userinfo — the form
 *    produced by NarcicTub's own Q+ MediaStore publishing pipeline. The
 *    platform provider enforces access; we never convert these to paths.
 *  - file:// URIs whose canonical path is CONTAINED in the app's own
 *    storage directories — the form produced by the pre-Q (API 26–28)
 *    legacy publisher into the app's external-files directory. Containment
 *    is checked on CANONICAL paths, so "..", "%2e%2e" and symlink tricks
 *    that escape the app tree are rejected.
 *
 * Everything else — http/https/ftp/javascript/data schemes, file:// outside
 * the app tree, malformed input, credentials, blank strings — is UNSAFE and
 * must never reach an Intent.
 *
 * Note the file:// leg does not weaken the approved Phase 6 API 26–28 split:
 * the legacy publisher is the only producer of these URIs, and they are
 * accepted only when they still point inside that same app-private tree.
 */
class MediaUriSafety(private val appDirs: List<File>) {

    enum class Kind { CONTENT, APP_FILE, UNSAFE }

    fun classify(uriText: String?): Kind {
        if (uriText.isNullOrBlank()) return Kind.UNSAFE
        val uri = try {
            URI(uriText)
        } catch (_: Exception) {
            return Kind.UNSAFE
        }
        val scheme = uri.scheme?.lowercase()
        if (uri.userInfo != null) return Kind.UNSAFE

        return when (scheme) {
            "content" -> if (uri.host.isNullOrBlank()) Kind.UNSAFE else Kind.CONTENT
            "file" -> if (isInsideAppTree(uri)) Kind.APP_FILE else Kind.UNSAFE
            else -> Kind.UNSAFE
        }
    }

    /** True when [uriText] passed the policy and may be handed to an Intent. */
    fun isSafelyOpenable(uriText: String?): Boolean = classify(uriText) != Kind.UNSAFE

    /**
     * The contained filesystem path for an APP_FILE URI; null for anything
     * else. Never returns paths for content:// URIs — those are only ever
     * resolved through the ContentResolver.
     */
    fun appFilePath(uriText: String): File? {
        if (classify(uriText) != Kind.APP_FILE) return null
        return try {
            File(URI(uriText).path)
        } catch (_: Exception) {
            null
        }
    }

    private fun isInsideAppTree(uri: URI): Boolean {
        if (uri.host != null && uri.host != "localhost") return false
        val path = uri.path ?: return false
        if (path.isEmpty()) return false
        val candidate = try {
            File(path).canonicalFile
        } catch (_: Exception) {
            return false
        }
        // Canonical containment: traversal forms collapse before comparison,
        // so an escaped path can never prefix-match an app directory.
        return canonicalDirs.any { candidate == it || candidate.path.startsWith(it.path + File.separator) }
    }

    private val canonicalDirs: List<File> by lazy {
        appDirs.mapNotNull { dir -> runCatching { dir.canonicalFile }.getOrNull() }
    }
}
