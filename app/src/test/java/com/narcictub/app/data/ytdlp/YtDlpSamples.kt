package com.narcictub.app.data.ytdlp

/** Trimmed but structurally faithful `yt-dlp --dump-single-json` payloads. */
internal object YtDlpSamples {

    /**
     * Storyboard + audio (m4a/opus) + a muxed 360p + video-only 720/1080/1440
     * (H.264 and VP9) + an HLS twin that must be ignored while real formats exist.
     */
    val YOUTUBE = """
    {
      "id": "abc123",
      "title": "Sample video",
      "duration": 212.0,
      "thumbnail": "https://i.ytimg.com/vi/abc123/hq.jpg",
      "formats": [
        {"format_id": "sb0", "ext": "mhtml", "vcodec": "none", "acodec": "none", "protocol": "mhtml"},
        {"format_id": "140", "ext": "m4a", "vcodec": "none", "acodec": "mp4a.40.2", "abr": 129.5, "tbr": 129.5, "filesize": 3400000, "protocol": "https"},
        {"format_id": "251", "ext": "webm", "vcodec": "none", "acodec": "opus", "abr": 135, "tbr": 135, "filesize": 3500000, "protocol": "https"},
        {"format_id": "18", "ext": "mp4", "vcodec": "avc1.42001E", "acodec": "mp4a.40.2", "width": 640, "height": 360, "tbr": 500, "filesize": 9000000, "protocol": "https"},
        {"format_id": "136", "ext": "mp4", "vcodec": "avc1.4d401f", "acodec": "none", "width": 1280, "height": 720, "tbr": 1000, "filesize": 20000000, "protocol": "https"},
        {"format_id": "247", "ext": "webm", "vcodec": "vp9", "acodec": "none", "width": 1280, "height": 720, "tbr": 1200, "filesize": 18000000, "protocol": "https"},
        {"format_id": "137", "ext": "mp4", "vcodec": "avc1.640028", "acodec": "none", "width": 1920, "height": 1080, "tbr": 2500, "filesize": 50000000, "protocol": "https"},
        {"format_id": "248", "ext": "webm", "vcodec": "vp9", "acodec": "none", "width": 1920, "height": 1080, "tbr": 2000, "filesize": 45000000, "protocol": "https"},
        {"format_id": "271", "ext": "webm", "vcodec": "vp9", "acodec": "none", "width": 2560, "height": 1440, "tbr": 6000, "filesize": 90000000, "protocol": "https"},
        {"format_id": "hls-1", "ext": "mp4", "vcodec": "avc1", "acodec": "mp4a", "height": 1080, "tbr": 9999, "protocol": "m3u8_native"}
      ]
    }
    """.trimIndent()

    /** Only HLS streams exist (what yt-dlp can return when no JS runtime is available). */
    val HLS_ONLY = """
    {
      "id": "hls1",
      "title": "Live-ish",
      "formats": [
        {"format_id": "hls-720", "ext": "mp4", "vcodec": "avc1", "acodec": "mp4a", "width": 1280, "height": 720, "tbr": 1500, "protocol": "m3u8_native"},
        {"format_id": "hls-360", "ext": "mp4", "vcodec": "avc1", "acodec": "mp4a", "width": 640, "height": 360, "tbr": 500, "protocol": "m3u8_native"}
      ]
    }
    """.trimIndent()

    /** A single plain file and no `formats` list. */
    val SINGLE_URL = """
    {
      "id": "one",
      "title": "One file",
      "url": "https://cdn.example.com/one.mp4",
      "ext": "mp4",
      "width": 720,
      "height": 1280,
      "duration": 15
    }
    """.trimIndent()

    /** Instagram carousel: a playlist whose first entry is the item. */
    val CAROUSEL = """
    {
      "_type": "playlist",
      "id": "post1",
      "title": "Post by someone",
      "entries": [
        {
          "id": "entry1",
          "formats": [
            {"format_id": "dash-audio", "ext": "m4a", "vcodec": "none", "acodec": "mp4a.40.2", "abr": 64, "protocol": "https"},
            {"format_id": "dash-video", "ext": "mp4", "vcodec": "avc1", "acodec": "none", "width": 720, "height": 1280, "tbr": 900, "protocol": "https"}
          ]
        }
      ]
    }
    """.trimIndent()
}
