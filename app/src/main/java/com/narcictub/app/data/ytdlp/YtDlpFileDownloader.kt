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
 * Work happens in a private sibling directory `narcictub_<id>.ytdlp` that is
 * always deleted afterwards (success, failure and cancellation alike). Only a
 * file named exactly `media.<ext>` counts as the result, so leftover
 * `media.f137.mp4`-style fragments of a failed merge are never mistaken for it.
 */
@Singleton
class YtDlpFileDownloader @Inject constructor(
    private val engine: YtDlpEngine,
) : FileDownloader {

    override suspend fun download(
        url: String,
        destination: File,
        onProgress: (DownloadProgress) -> Unit,
    ): DownloadFileResult {
        val parsed = YtDlpUrl.parse(url)
        // Defense in depth: the router already checked, but this class must
        // never hand an arbitrary host to yt-dlp's generic extractor.
        if (YtDlpUrl.providerOf(parsed.pageUrl) == MediaProvider.UNKNOWN) {
            throw DownloadException.Policy("Destination blocked: unsupported source")
        }

        val workDir = File(destination.parentFile, destination.nameWithoutExtension + ".ytdlp")
        val processId = "narcictub-" + destination.nameWithoutExtension
        try {
            workDir.deleteRecursively()
            if (!workDir.mkdirs()) throw DownloadException.Io(IOException("cannot create work directory"))

            try {
                engine.download(parsed.pageUrl, parsed.formatSpec, workDir, processId) { percent, line ->
                    YtDlpProgress.from(percent, line)?.let(onProgress)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: DownloadException) {
                throw e
            } catch (e: Exception) {
                throw DownloadException.Extraction(
                    YtDlpErrors.downloadMessage(YtDlpErrors.reasonOf(e)),
                )
            }

            val output = workDir.listFiles()
                ?.filter { it.isFile && RESULT_NAME.matches(it.name) }
                ?.maxByOrNull { it.length() }
                ?: throw DownloadException.Extraction("The download finished but no media file was produced")
            if (output.length() == 0L) throw DownloadException.Io(IOException("empty output"))

            try {
                if (destination.exists()) destination.delete()
                if (!output.renameTo(destination)) {
                    output.copyTo(destination, overwrite = true)
                    output.delete()
                }
            } catch (e: IOException) {
                throw DownloadException.Io(e)
            }

            val extension = output.extension.lowercase()
            val bytes = destination.length()
            onProgress(DownloadProgress(downloadedBytes = bytes, totalBytes = bytes))
            return DownloadFileResult(
                bytesDownloaded = bytes,
                contentType = YtDlpMime.forDownloadedFile(extension),
                fileExtension = extension.ifEmpty { null },
            )
        } finally {
            runCatching { workDir.deleteRecursively() }
        }
    }

    private companion object {
        val RESULT_NAME = Regex("^media\\.[A-Za-z0-9]{1,8}$")
    }
}
