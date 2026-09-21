package com.narcictub.app.data.ytdlp

/** File-extension → MIME mapping for the containers yt-dlp/ffmpeg produce. */
internal object YtDlpMime {

    fun video(ext: String?): String? = when (ext?.lowercase()) {
        "mp4" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        "3gp" -> "video/3gpp"
        else -> null
    }

    fun audio(ext: String?): String? = when (ext?.lowercase()) {
        "m4a", "mp4" -> "audio/mp4"
        "webm" -> "audio/webm"
        "opus", "ogg" -> "audio/ogg"
        "mp3" -> "audio/mpeg"
        "aac" -> "audio/aac"
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        else -> null
    }

    /** MIME for a finished download, decided by its real extension. */
    fun forDownloadedFile(ext: String?): String? = when (ext?.lowercase()) {
        "m4a", "mp3", "opus", "ogg", "aac", "flac", "wav" -> audio(ext)
        else -> video(ext)
    }
}
