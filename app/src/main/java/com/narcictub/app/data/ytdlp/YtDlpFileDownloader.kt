package com.narcictub.app.data.ytdlp

import com.narcictub.app.domain.downloader.DownloadException
import com.narcictub.app.domain.downloader.DownloadFileResult
import com.narcictub.app.domain.downloader.FileDownloader
import com.narcictub.app.domain.model.DownloadProgress
import com.narcictub.app.domain.model.MediaProvider
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [FileDownloader] for YouTube / Instagram page URLs: yt-dlp does the network
 * work (and ffmpeg merges separate video + audio streams), then the single
 * finished file is moved to the repository's staging file — from that point
 * the normal publish-to-MediaStore / history flow is unchanged.
 *
 * Work happens in a private sibling directory `narcictub_<id>.ytdlp`, deleted
 * on success or genuine failure. A CANCELLATION (which may be a pause — see
 * [workDirFor] and DownloadRepositoryImpl) deliberately leaves it in place,
 * since yt-dlp resumes a partial download of the same destination on its own.
 * Only a file named exactly `media.<ext>` counts as the result, so leftover
 * `media.f137.mp4`-style fragments of a failed merge are never mistaken for it.
 */
@Singleton
class YtDlpFileDownloader @Inject constructor(
    private val engine: YtDlpEngine,
) : FileDownloader {

    override suspend fun download(
        url: String,
        destination: File,
        resumeFromBytes: Long,
        onProgress: (DownloadProgress) -> Unit,
    ): DownloadFileResult {
        val parsed = YtDlpUrl.parse(url)
        // Defense in depth: the router already checked, but this class must
        // never hand an arbitrary host to yt-dlp's generic extractor.
        if (YtDlpUrl.providerOf(parsed.pageUrl) == MediaProvider.UNKNOWN) {
            throw DownloadException.Policy("Destination blocked: unsupported source")
        }

        val workDir = workDirFor(destination)
        val processId = "narcictub-" + destination.nameWithoutExtension
        // A resume (resumeFromBytes > 0, from a previous PAUSE — see
        // DownloadRepositoryImpl.pause()) keeps whatever partial files
        // yt-dlp left behind last time: yt-dlp continues a partial download
        // of the same destination on its own (--continue is on by default),
        // so simply not wiping the directory is enough to resume. A fresh
        // attempt always starts from a clean directory.
        if (resumeFromBytes <= 0L) {
            workDir.deleteRecursively()
        }
        if (!workDir.exists() && !workDir.mkdirs()) {
            throw DownloadException.Io(IOException("cannot create work directory"))
        }

        try {
            engine.download(parsed.pageUrl, parsed.formatSpec, workDir, processId) { percent, line ->
                YtDlpProgress.from(percent, line)?.let(onProgress)
            }
        } catch (e: CancellationException) {
            // Might be a pause: the repository decides whether the work
            // directory survives (see DownloadRepositoryImpl's
            // pausingIds/cleanupStagingFor) — never delete it here.
            throw e
        } catch (e: DownloadException) {
            workDir.deleteRecursively()
            throw e
        } catch (e: Exception) {
            workDir.deleteRecursively()
            throw DownloadException.Extraction(
                YtDlpErrors.downloadMessage(YtDlpErrors.reasonOf(e)),
            )
        }

        val output = workDir.listFiles()
            ?.filter { it.isFile && RESULT_NAME.matches(it.name) }
            ?.maxByOrNull { it.length() }
        if (output == null) {
            workDir.deleteRecursively()
            throw DownloadException.Extraction("The download finished but no media file was produced")
        }
        if (output.length() == 0L) {
            workDir.deleteRecursively()
            throw DownloadException.Io(IOException("empty output"))
        }

        try {
            if (destination.exists()) destination.delete()
            if (!output.renameTo(destination)) {
                output.copyTo(destination, overwrite = true)
                output.delete()
            }
        } catch (e: IOException) {
            workDir.deleteRecursively()
            throw DownloadException.Io(e)
        }

        // The finished file has been moved out; nothing worth keeping remains.
        workDir.deleteRecursively()

        val extension = output.extension.lowercase()
        val bytes = destination.length()
        onProgress(DownloadProgress(downloadedBytes = bytes, totalBytes = bytes))
        return DownloadFileResult(
            bytesDownloaded = bytes,
            contentType = YtDlpMime.forDownloadedFile(extension),
            fileExtension = extension.ifEmpty { null },
        )
    }

    companion object {
        private val RESULT_NAME = Regex("^media\\.[A-Za-z0-9]{1,8}$")

        /**
         * Sibling working directory yt-dlp/ffmpeg write into for a given
         * final [destination] — public so the repository can clean it up
         * on a genuine cancel/removal/interrupted-row sweep (a pause
         * deliberately leaves it in place; see the class doc).
         */
        fun workDirFor(destination: File): File =
            File(destination.parentFile, destination.nameWithoutExtension + ".ytdlp")
    }
}
