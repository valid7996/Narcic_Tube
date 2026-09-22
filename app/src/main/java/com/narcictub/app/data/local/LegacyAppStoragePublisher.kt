package com.narcictub.app.data.local

import android.content.Context
import android.os.Environment
import com.narcictub.app.domain.DownloadLocationPolicy
import com.narcictub.app.domain.model.DownloadLocation
import java.io.File
import java.io.IOException

/**
 * API 26–28 publisher. Contains NO Q-only MediaStore symbol (pinned by
 * MediaStoreFileWriterTest): on pre-Q devices the Q collections do not
 * exist, and the app deliberately holds no legacy WRITE_EXTERNAL_STORAGE
 * permission (shared collections are not writable without it), so downloads
 * are copied into the app's own external files directory — permission-free,
 * user-visible over USB/MTP, stable for the install lifetime — and addressed
 * by a file:// URI.
 */
internal object LegacyAppStoragePublisher {

    /**
     * Copies [stagingFile] into the folder for [location] under [safeName]
     * (uniquified on collision) and returns the published file.
     */
    fun publish(
        context: Context,
        location: DownloadLocation,
        stagingFile: File,
        safeName: String,
    ): File {
        val directory = targetDirectory(context, location).apply { mkdirs() }
        val target = claimTarget(directory, safeName)
        try {
            stagingFile.inputStream().use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output, COPY_BUFFER_BYTES)
                }
            }
        } catch (e: Exception) {
            runCatching { target.delete() }
            throw e
        }
        if (target.length() != stagingFile.length()) {
            runCatching { target.delete() }
            throw IOException("incomplete copy")
        }
        return target
    }

    /** App-external folder mirroring the user's chosen collection. */
    private fun targetDirectory(context: Context, location: DownloadLocation): File {
        val folder = when (location) {
            DownloadLocation.DOWNLOADS -> Environment.DIRECTORY_DOWNLOADS
            DownloadLocation.MUSIC -> Environment.DIRECTORY_MUSIC
            DownloadLocation.MOVIES -> Environment.DIRECTORY_MOVIES
            DownloadLocation.DCIM -> Environment.DIRECTORY_DCIM
        }
        // getExternalFilesDir creates the folder; null only when external
        // storage is unavailable → internal fallback. Same app subfolder
        // concept as the Q+ path, so files are never loose at the root.
        val base = context.getExternalFilesDir(folder) ?: File(context.filesDir, folder)
        return File(base, DownloadLocationPolicy.legacySubFolder())
    }

    /**
     * Atomically claims a not-yet-existing file name (createNewFile) and
     * uniquifies on collision — "name (1).ext", "name (2).ext", … — so two
     * concurrent publishes never write the same file.
     */
    private fun claimTarget(directory: File, safeName: String): File {
        val base = safeName.substringBeforeLast('.', missingDelimiterValue = safeName)
        val extension = if (safeName.contains('.')) safeName.substringAfterLast('.', "") else ""
        var candidate = File(directory, safeName)
        var counter = 0
        while (!candidate.createNewFile()) {
            counter += 1
            if (counter > MAX_NAME_COLLISIONS) throw IOException("no free file name")
            val name = if (extension.isEmpty()) "$base ($counter)" else "$base ($counter).$extension"
            candidate = File(directory, name)
        }
        return candidate
    }

    private const val COPY_BUFFER_BYTES = 16 * 1024
    private const val MAX_NAME_COLLISIONS = 999
}
