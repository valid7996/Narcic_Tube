package com.narcictub.app.ui.common

import com.narcictub.app.domain.resolver.MediaResolveException

/**
 * Safe, user-readable mapping of resolve failures — shared by every screen
 * that resolves a URL (Home form and the Share download screen), so the
 * wording never drifts. The user never sees URLs, query strings,
 * credentials, paths, stack traces or raw exception messages.
 */
internal object ResolveErrorMessages {

    fun messageFor(error: Throwable): String = when (error) {
        is MediaResolveException.UnsupportedSource ->
            "This isn't a direct media file link — dedicated platforms aren't supported yet."
        is MediaResolveException.UnsupportedProvider ->
            when (error.provider) {
                com.narcictub.app.domain.model.MediaProvider.SPOTIFY ->
                    "Spotify streams are DRM-protected — downloading them isn't possible."
                else ->
                    "${error.provider.displayName} links aren't supported yet — " +
                        "extraction for this provider hasn't been implemented."
            }
        is MediaResolveException.ExtractionUnavailable ->
            // PHASE 19: provider is recognized but no legitimate, authorized
            // retrieval path exists — the honest message, no "Download failed".
            "Content from ${error.provider.displayName} can't be retrieved yet — " +
                "there's no legitimate access path available to this app."
        is MediaResolveException.ExtractionFailed -> when (error.reason) {
            MediaResolveException.ExtractionFailed.Reason.LOGIN_REQUIRED ->
                "${error.provider.displayName} asked for a login to show this link. " +
                    "Import your browser's cookies.txt in Settings and try again."
            MediaResolveException.ExtractionFailed.Reason.UNAVAILABLE ->
                "This media is private, removed, or blocked in your region."
            MediaResolveException.ExtractionFailed.Reason.NO_MEDIA ->
                "No downloadable video was found at this link."
            MediaResolveException.ExtractionFailed.Reason.RATE_LIMITED ->
                "${error.provider.displayName} is limiting requests right now. Try again in a while."
            MediaResolveException.ExtractionFailed.Reason.NETWORK ->
                "Couldn't reach ${error.provider.displayName}. Check your connection and try again."
            MediaResolveException.ExtractionFailed.Reason.ENGINE_UNAVAILABLE ->
                "The download engine couldn't start. Restart the app and try again."
            MediaResolveException.ExtractionFailed.Reason.OTHER ->
                "Couldn't read this ${error.provider.displayName} link. The site may have changed — " +
                    "restart the app so the engine can update, then try again."
        }
        is MediaResolveException.Http ->
            "The source server answered with an error (HTTP ${error.statusCode})."
        is MediaResolveException.Network ->
            "Couldn't reach the source server. Check your connection and try again."
        is MediaResolveException.Policy ->
            "This link points to a blocked destination and can't be used."
        else -> "Couldn't resolve that link."
    }
}
