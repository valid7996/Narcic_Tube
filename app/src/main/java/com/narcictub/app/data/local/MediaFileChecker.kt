package com.narcictub.app.data.local

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * PHASE 10 — checks whether a completed record's published file still
 * resolves, WITHOUT exposing paths and WITHOUT trusting the stored URI:
 * [MediaUriSafety] gates every lookup, so file:// outside the app's own
 * tree and any foreign scheme can never touch the filesystem or a provider.
 *
 * - content:// → ContentResolver.openFileDescriptor (a descriptor is the
 *   honest "opens right now" test; the path is never read)
 * - app-owned file:// → File.isFile on the contained path
 * - anything else → false (unavailable), never an exception to the caller
 */
@Singleton
open class MediaFileChecker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val uriSafety: MediaUriSafety,
) {

    /** Open seam: test doubles script the backend answer directly. */
    open suspend fun isAvailable(uriText: String): Boolean = withContext(Dispatchers.IO) {
        when (uriSafety.classify(uriText)) {
            MediaUriSafety.Kind.CONTENT -> contentResolvable(uriText)
            MediaUriSafety.Kind.APP_FILE -> appFilePresent(uriText)
            MediaUriSafety.Kind.UNSAFE -> false
        }
    }

    /**
     * Resolves a content URI through the provider. Missing rows, revoked
     * grants, security exceptions and malformed URIs all mean "not
     * available" — no distinction is fabricated.
     */
    protected open fun contentResolvable(uriText: String): Boolean = try {
        context.contentResolver
            .openFileDescriptor(Uri.parse(uriText), "r")
            ?.use { /* descriptor opened — the file resolves */ } != null
    } catch (_: Exception) {
        false
    }

    /** Existence check for an app-contained file:// path (pre-Q records). */
    protected open fun appFilePresent(uriText: String): Boolean {
        val file = uriSafety.appFilePath(uriText) ?: return false
        return try {
            file.isFile
        } catch (_: Exception) {
            false
        }
    }
}
