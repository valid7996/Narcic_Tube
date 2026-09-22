package com.narcictub.app.data.local

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import com.narcictub.app.domain.model.DownloadLocation
import java.io.File
import java.io.IOException

/**
 * API 29+ publisher — the ONLY file allowed to reference the Q-only
 * MediaStore symbols (MediaStore.Downloads, VOLUME_EXTERNAL_PRIMARY,
 * RELATIVE_PATH, IS_PENDING). [MediaStoreFileWriter] dispatches here solely
 * on Q+, so these classes are never loaded on API 26–28 (review H-1: pre-Q
 * devices used to hit NoClassDefFoundError — an Error, invisible to
 * catch(Exception) — leaving the download row stuck at DOWNLOADING;
 * MediaStoreFileWriterTest pins the dispatch split and scans the pre-Q
 * sources for these tokens).
 *
 * Publication protocol:
 *  1. insert with the pending flag set — a partial file is never surfaced
 *  2. stream + flush
 *  3. clear the pending flag — the finished download becomes visible
 *  4. on ANY failure the pending entry is deleted again
 */
internal object QPlusMediaStorePublisher {

    fun publish(
        resolver: ContentResolver,
        location: DownloadLocation,
        stagingFile: File,
        safeName: String,
        mimeType: String?,
        subDirectory: String?,
    ): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream")
            if (subDirectory != null) put(MediaStore.MediaColumns.RELATIVE_PATH, subDirectory)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val pendingUri = resolver.insert(collectionFor(location), values)
            ?: throw IOException("MediaStore insert failed")

        try {
            resolver.openOutputStream(pendingUri)?.use { output ->
                stagingFile.inputStream().use { input ->
                    input.copyTo(output, COPY_BUFFER_BYTES)
                }
                output.flush()
            } ?: throw IOException("MediaStore stream unavailable")

            resolver.update(
                pendingUri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            return pendingUri
        } catch (e: Exception) {
            // Never leave a dangling/partial published entry behind.
            runCatching { resolver.delete(pendingUri, null, null) }
            throw e
        }
    }

    /** Shared collections; the dedicated ones below only exist on Q+. */
    private fun collectionFor(location: DownloadLocation): Uri = when (location) {
        DownloadLocation.DOWNLOADS -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
        DownloadLocation.MUSIC -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        DownloadLocation.MOVIES -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        DownloadLocation.DCIM -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    }

    private const val COPY_BUFFER_BYTES = 16 * 1024
}
