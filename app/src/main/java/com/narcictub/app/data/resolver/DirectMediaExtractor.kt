package com.narcictub.app.data.resolver

import com.narcictub.app.domain.model.MediaProvider
import com.narcictub.app.domain.resolver.MediaExtractor
import com.narcictub.app.domain.resolver.MediaProviderDetector
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PHASE 17: the fallback intake for URLs that are not recognized platform
 * pages — direct HTTP/HTTPS media links. Extraction is delegated to the
 * Phase 8 [DirectMediaResolver]; every security policy (destination policy,
 * redirect validation, content checks) applies unchanged.
 *
 * supports() claims any URL whose host is not a recognized provider host.
 * Recognized providers (YouTube, Instagram, …) require their own explicitly
 * implemented extractors — recognition alone never claims support.
 */
@Singleton
class DirectMediaExtractor @Inject constructor(
    private val resolver: DirectMediaResolver,
) : MediaExtractor {

    // Lowest priority: recognized providers with their own extractors
    // always win the deterministic registry selection.
    override val priority: Int = 0

    override fun supports(url: String): Boolean =
        MediaProviderDetector.detect(url) == MediaProvider.UNKNOWN

    override suspend fun extract(url: String) = resolver.resolve(url)
}
