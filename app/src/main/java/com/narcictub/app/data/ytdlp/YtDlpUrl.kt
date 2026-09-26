package com.narcictub.app.data.ytdlp

import com.narcictub.app.domain.model.MediaProvider
import java.net.URI

/**
 * Strict recognition of the pages that are handed to yt-dlp, plus the tiny
 * codec that carries the chosen quality through the (persisted) source URL.
 *
 * SECURITY: yt-dlp has a "generic" extractor that will fetch ANY URL it is
 * given, which would be an SSRF hole. So nothing reaches yt-dlp unless this
 * object independently confirms an http(s) URL on youtube.com / youtu.be /
 * instagram.com (or a real subdomain), with no embedded credentials.
 * Lookalikes such as `youtube.com.evil.com` or `evilinstagram.com`, IP
 * literals and other schemes all resolve to [MediaProvider.UNKNOWN].
 *
 * QUALITY CARRIER: a resolved variant is identified by the page URL plus a
 * URL fragment `#nt-f=<yt-dlp format id>[+<audio format id>]`. Fragments are
 * never sent to servers, survive the history table (so Retry keeps the same
 * quality) and need no schema change. The spec is whitelisted strictly, so
 * it can never smuggle a yt-dlp option.
 */
internal object YtDlpUrl {

    private val YOUTUBE_HOSTS = setOf(
        "youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be",
    )
    private val TIKTOK_HOSTS = setOf("tiktok.com")
    private val TWITTER_HOSTS = setOf("twitter.com", "x.com")
    private val FACEBOOK_HOSTS = setOf("facebook.com", "fb.com", "fb.watch")
    private val PINTEREST_HOSTS = setOf("pinterest.com", "pin.it")
    private val SNAPCHAT_HOSTS = setOf("snapchat.com")
    private val SOUNDCLOUD_HOSTS = setOf("soundcloud.com")
    private val SPOTIFY_HOSTS = setOf("spotify.com")

    private const val FRAGMENT_PREFIX = "nt-f="

    /**
     * One or two format ids joined by '+'. An id starts with a letter or digit
     * (never '-', so it can never look like a command-line option) and then
     * holds letters, digits, '.', '_' or '-'.
     */
    private const val ID = "[A-Za-z0-9][A-Za-z0-9._-]{0,63}"
    private val FORMAT_SPEC = Regex("^$ID(\\+$ID)?$")
    private val FORMAT_ID = Regex("^$ID$")

    fun providerOf(url: String): MediaProvider {
        val uri = try {
            URI(url)
        } catch (_: Exception) {
            return MediaProvider.UNKNOWN
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") return MediaProvider.UNKNOWN
        if (!uri.userInfo.isNullOrEmpty()) return MediaProvider.UNKNOWN
        val host = uri.host?.lowercase() ?: return MediaProvider.UNKNOWN
        return when {
            host in YOUTUBE_HOSTS || host.endsWith(".youtube.com") -> MediaProvider.YOUTUBE
            host == "instagram.com" || host.endsWith(".instagram.com") -> MediaProvider.INSTAGRAM
            host in TIKTOK_HOSTS || host.endsWith(".tiktok.com") -> MediaProvider.TIKTOK
            host in TWITTER_HOSTS || host.endsWith(".twitter.com") || host.endsWith(".x.com") -> MediaProvider.TWITTER
            host in FACEBOOK_HOSTS || host.endsWith(".facebook.com") || host.endsWith(".fb.com") || host == "fb.watch" -> MediaProvider.FACEBOOK
            host in PINTEREST_HOSTS || host.endsWith(".pinterest.com") || host == "pin.it" -> MediaProvider.PINTEREST
            host in SNAPCHAT_HOSTS || host.endsWith(".snapchat.com") -> MediaProvider.SNAPCHAT
            host in SOUNDCLOUD_HOSTS || host.endsWith(".soundcloud.com") -> MediaProvider.SOUNDCLOUD
            host in SPOTIFY_HOSTS || host.endsWith(".spotify.com") -> MediaProvider.SPOTIFY
            else -> MediaProvider.UNKNOWN
        }
    }

    fun isSafeFormatId(id: String): Boolean = FORMAT_ID.matches(id)

    /** The page URL (fragment stripped) plus the validated quality spec, if any. */
    data class Parsed(val pageUrl: String, val formatSpec: String?)

    fun parse(url: String): Parsed {
        val hash = url.indexOf('#')
        if (hash < 0) return Parsed(url, null)
        val page = url.substring(0, hash)
        val fragment = url.substring(hash + 1)
        val spec = if (fragment.startsWith(FRAGMENT_PREFIX)) {
            fragment.removePrefix(FRAGMENT_PREFIX).takeIf { FORMAT_SPEC.matches(it) }
        } else {
            null
        }
        return Parsed(page, spec)
    }

    /** Page URL + `#nt-f=<spec>`. [spec] must already be a valid format spec. */
    fun withFormat(pageUrl: String, spec: String): String {
        require(FORMAT_SPEC.matches(spec)) { "unsafe format spec" }
        return parse(pageUrl).pageUrl + "#" + FRAGMENT_PREFIX + spec
    }
}
