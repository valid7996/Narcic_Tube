package com.narcictub.app.domain.model

/**
 * PHASE 17: media platforms NarcicTub can RECOGNIZE by URL. Recognition is
 * host-based and deliberately separate from extraction support — a provider
 * being detected here does NOT mean its media can be extracted or
 * downloaded (extraction must be explicitly implemented by an extractor).
 *
 * The yt-dlp-backed extractor supports every provider here EXCEPT Spotify:
 * Spotify audio is DRM-protected and no legitimate download path exists.
 */
enum class MediaProvider(val displayName: String) {
    YOUTUBE("YouTube"),
    INSTAGRAM("Instagram"),
    TIKTOK("TikTok"),
    TWITTER("X (Twitter)"),
    FACEBOOK("Facebook"),
    PINTEREST("Pinterest"),
    SNAPCHAT("Snapchat"),
    SOUNDCLOUD("SoundCloud"),
    SPOTIFY("Spotify"),
    UNKNOWN("Unknown"),
}
