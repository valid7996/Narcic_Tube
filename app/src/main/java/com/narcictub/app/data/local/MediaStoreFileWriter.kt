package com.narcictub.app.data.local

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.narcictub.app.domain.FileNameSanitizer
import com.narcictub.app.domain.model.AppSettings
import com.narcictub.app.domain.model.DownloadLocation
import com.narcictub.app.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Moves a fully-downloaded staging file into MediaStore and returns the
 * persistent content URI. Appends to shared collections only via
 * MediaStore.Downloads (or the user-configured collection on Android 10+).
 *
 * Safety properties:
 *  - DISPLAY_NAME passes through FileNameSanitizer (no traversal)
 *  - IS_PENDING=1 while copying; cleared only after the stream is flushed —
 *    a partial file is never exposed as a completed download
 *  - on failure the pending entry is deleted
 *  - no storage permission needed (scoped storage, app-contributed entries)
 */
@Singleton
open class MediaStoreFileWriter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
) {

    /**
     * Publishes [stagingFile] under [displayName] with MIME [mimeType]
     * (fallback "application/octet-stream" when the server didn't declare).
     * Returns the final content:// URI.
     */
    suspend fun publish(
        stagingFile: File,
        displayName: String,
        mimeType: String?,
        subDirectory: String? = null,
    ): Uri = doPublish(stagingFile, displayName, mimeType, subDirectory)

    /** Overridable seam for tests. */
    protected open suspend fun doPublish(
        stagingFile: File,
        displayName: String,
        mimeType: String?,
        subDirectory: String?,
    ): Uri = withContext(Dispatchers.IO) {
        val location = settingsRepository.settings.first().downloadLocation
        val collection = collectionUriFor(location)
        val resolver = context.contentResolver

        val safeName = com.narcictub.app.domain.FileNameSanitizer.sanitize(
            displayName,
            fallback = stagingFile.nameWithoutExtension.ifEmpty { "download" },
        )

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream")
            if (subDirectory != null) put(MediaStore.MediaColumns.RELATIVE_PATH, subDirectory)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val pendingUri = resolver.insert(collection, values)
            ?: throw java.io.IOException("MediaStore insert failed")

        try {
            resolver.openOutputStream(pendingUri)?.use { output ->
                stagingFile.inputStream().use { input ->
                    input.copyTo(output, DEFAULT_BUFFER_SIZE)
                }
                output.flush()
            } ?: throw java.io.IOException("MediaStore stream unavailable")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val finalizeValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                resolver.update(pendingUri, finalizeValues, null, null)
            }
            pendingUri
        } catch (e: Exception) {
            // Never leave a dangling/partial published entry behind.
            runCatching { resolver.delete(pendingUri, null, null) }
            throw e
        }
    }

    /** Deletes a published entry — used when finalization failed downstream. */
    suspend fun delete(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        context.contentResolver.delete(uri, null, null) > 0
    }

    private fun collectionUriFor(location: DownloadLocation): Uri = when (location) {
        DownloadLocation.DOWNLOADS -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
        // The dedicated collections below only exist on Android 10+.
        else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            when (location) {
                DownloadLocation.MUSIC -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                DownloadLocation.MOVIES -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                DownloadLocation.DCIM -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                DownloadLocation.DOWNLOADS -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
            }
        } else {
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        }
    }
}
