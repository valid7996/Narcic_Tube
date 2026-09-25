package com.narcictub.app.data.local

import android.content.Context
import android.net.Uri
import android.os.Build
import com.narcictub.app.domain.FileNameSanitizer
import com.narcictub.app.domain.model.DownloadLocation
import com.narcictub.app.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Publishes a fully-downloaded staging file into persistent storage and
 * returns a stable URI for the history row.
 *
 * API SPLIT (review H-1): API 26–28 and API 29+ take completely separate
 * implementations, selected by [deviceSdkInt]:
 *  - API 29+  → [QPlusMediaStorePublisher]: the platform's shared
 *    downloads/audio/video/images collections with the pending flag, so a
 *    partial file is never exposed as a finished download.
 *  - API 26–28 → [LegacyAppStoragePublisher]: pre-Q devices cannot resolve
 *    the Q-only platform classes (NoClassDefFoundError — an Error no
 *    `catch (Exception)` swallows, which used to leave rows stuck in
 *    DOWNLOADING), and the app deliberately holds no legacy storage
 *    permission, so the file is copied into the app's own external files
 *    directory and addressed by a file:// URI.
 *
 * Safety properties (both paths):
 *  - the display name passes through FileNameSanitizer (no traversal)
 *  - a partial publish is never left behind (delete on failure)
 *  - [delete] handles both content:// and file:// URIs
 *  - no storage permission needed (scoped storage / app-contributed storage)
 */
@Singleton
open class MediaStoreFileWriter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
) {

    /**
     * Publishes [stagingFile] under [displayName] with MIME [mimeType]
     * (fallback "application/octet-stream" when the server didn't declare).
     * Returns the persistent URI for the finished file.
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
        val settings = settingsRepository.settings.first()
        val safeName = FileNameSanitizer.sanitize(
            displayName,
            fallback = stagingFile.nameWithoutExtension.ifEmpty { "download" },
        )
        // Custom folder override first (any API level). If it can no longer
        // be written (grant revoked, folder removed, name collision policy),
        // fall back to the platform path — the download must not die because
        // of the override.
        settings.customDownloadFolderUri?.let { treeUriText ->
            try {
                return@withContext publishViaSaf(treeUriText, stagingFile, safeName, mimeType)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // fall through to the platform publishers below
            }
        }
        if (deviceSdkInt() >= Build.VERSION_CODES.Q) {
            publishViaQPlus(
                settings.downloadLocation,
                stagingFile,
                safeName,
                mimeType,
                // Default: an app-named subfolder of the chosen collection
                // (Downloads/NarcicTub, Music/NarcicTub, …) instead of the
                // collection root — callers that pass an explicit relative
                // path keep theirs.
                subDirectory ?: defaultSubDirectory(settings.downloadLocation),
            )
        } else {
            publishViaLegacy(settings.downloadLocation, stagingFile, safeName)
        }
    }

    /** Device API level — seam for tests pinning the 26–28 vs 29+ split. */
    protected open fun deviceSdkInt(): Int = Build.VERSION.SDK_INT

    /**
     * App-named subfolder of the chosen collection — literal platform
     * directory segments (stable names, never user content). The actual
     * relative-path column reference lives only in the Q+ publisher.
     */
    private fun defaultSubDirectory(location: DownloadLocation): String = when (location) {
        DownloadLocation.DOWNLOADS -> "Download/NarcicTub"
        DownloadLocation.MUSIC -> "Music/NarcicTub"
        DownloadLocation.MOVIES -> "Movies/NarcicTub"
        DownloadLocation.DCIM -> "DCIM/NarcicTub"
    }

    /**
     * Custom-folder path (SAF document tree, any API level). Takes the raw
     * persisted URI string — parsing stays inside the production seam so JVM
     * tests can intercept without touching android.net.Uri statics.
     */
    protected open fun publishViaSaf(
        treeUriText: String,
        stagingFile: File,
        safeName: String,
        mimeType: String?,
    ): Uri = SafFolderPublisher.publish(
        resolver = context.contentResolver,
        treeUri = Uri.parse(treeUriText),
        stagingFile = stagingFile,
        safeName = safeName,
        mimeType = mimeType,
    )

    /**
     * API 29+ path. Delegates to [QPlusMediaStorePublisher]; the Q-only
     * MediaStore symbols live only in that file, which is class-loaded only
     * on Q+ devices.
     */
    protected open fun publishViaQPlus(
        location: DownloadLocation,
        stagingFile: File,
        safeName: String,
        mimeType: String?,
        subDirectory: String?,
    ): Uri = QPlusMediaStorePublisher.publish(
        resolver = context.contentResolver,
        location = location,
        stagingFile = stagingFile,
        safeName = safeName,
        mimeType = mimeType,
        subDirectory = subDirectory,
    )

    /**
     * API 26–28 path. Delegates to [LegacyAppStoragePublisher]; contains no
     * Q-only platform symbol (pinned by MediaStoreFileWriterTest). The Q+
     * relative-subdirectory concept does not exist pre-Q — the chosen
     * collection decides the folder instead.
     */
    protected open fun publishViaLegacy(
        location: DownloadLocation,
        stagingFile: File,
        safeName: String,
    ): Uri = toFileUri(LegacyAppStoragePublisher.publish(context, location, stagingFile, safeName))

    /** file:// conversion seam (android.net.Uri statics are not unit-testable). */
    protected open fun toFileUri(file: File): Uri = Uri.fromFile(file)

    /**
     * Deletes a published entry — used when finalization failed downstream.
     * Handles both the Q+ content:// form and the pre-Q file:// form.
     * Open so tests can record deletions (Phase 10 record-vs-file semantics).
     */
    open suspend fun delete(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        if (uri.scheme == "content") {
            context.contentResolver.delete(uri, null, null) > 0
        } else {
            uri.path?.let { path -> File(path).delete() } ?: false
        }
    }
}
