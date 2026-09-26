package com.narcictub.app.data.downloader

import com.narcictub.app.data.ytdlp.YtDlpFileDownloader
import com.narcictub.app.data.ytdlp.YtDlpUrl
import com.narcictub.app.domain.downloader.DownloadFileResult
import com.narcictub.app.domain.downloader.FileDownloader
import com.narcictub.app.domain.model.DownloadProgress
import com.narcictub.app.domain.model.MediaProvider
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The [FileDownloader] the download queue actually uses. YouTube / Instagram
 * page URLs go to yt-dlp; everything else keeps the policy-gated
 * HttpURLConnection downloader with its exact previous behavior (SSRF
 * checks on every hop, streaming, cancellation).
 */
@Singleton
class RoutingFileDownloader @Inject constructor(
    private val direct: HttpUrlConnectionDownloader,
    private val ytDlp: YtDlpFileDownloader,
) : FileDownloader {

    override suspend fun download(
        url: String,
        destination: File,
        onProgress: (DownloadProgress) -> Unit,
    ): DownloadFileResult {
        val pageUrl = YtDlpUrl.parse(url).pageUrl
        return if (YtDlpUrl.providerOf(pageUrl) != MediaProvider.UNKNOWN) {
            ytDlp.download(url, destination, onProgress)
        } else {
            direct.download(url, destination, onProgress)
        }
    }
}
