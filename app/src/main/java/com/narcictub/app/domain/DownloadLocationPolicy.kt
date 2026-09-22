package com.narcictub.app.domain

import com.narcictub.app.domain.model.DownloadLocation

/**
 * Decides WHERE a finished download is published, and keeps every
 * destination organized under one app-named subfolder instead of dropping
 * loose files into the user's shared Download/Music/Movies/Pictures.
 *
 *  - A clearly video/audio MIME type always lands in the matching system
 *    collection (Movies for video, Music for audio) REGARDLESS of the
 *    manual Settings choice, so it shows up in the phone's own Gallery/
 *    Video and Music apps — the same place any other app's video/audio
 *    downloads would appear.
 *  - Anything else (documents, images, unknown/generic types) respects the
 *    location the user picked in Settings (defaults to Downloads).
 */
object DownloadLocationPolicy {

    /** Subfolder created inside every collection: Download/NarcicTub, Movies/NarcicTub, ... */
    const val APP_FOLDER_NAME = "NarcicTub"

    fun effectiveLocation(mimeType: String?, settingsLocation: DownloadLocation): DownloadLocation {
        val type = mimeType?.substringBefore(';')?.trim()?.lowercase()
        return when {
            type?.startsWith("video/") == true -> DownloadLocation.MOVIES
            type?.startsWith("audio/") == true -> DownloadLocation.MUSIC
            else -> settingsLocation
        }
    }

    /** Q+ MediaStore RELATIVE_PATH for [location] — always inside the app's own subfolder. */
    fun relativePath(location: DownloadLocation): String = "${topLevelFolder(location)}/$APP_FOLDER_NAME/"

    /** Pre-Q (API 26–28) app-external folder name for [location] — same app subfolder concept. */
    fun legacySubFolder(): String = APP_FOLDER_NAME

    private fun topLevelFolder(location: DownloadLocation): String = when (location) {
        DownloadLocation.DOWNLOADS -> "Download"
        DownloadLocation.MUSIC -> "Music"
        DownloadLocation.MOVIES -> "Movies"
        DownloadLocation.DCIM -> "DCIM"
    }
}
