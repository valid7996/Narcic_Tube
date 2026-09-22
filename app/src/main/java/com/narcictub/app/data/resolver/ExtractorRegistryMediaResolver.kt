package com.narcictub.app.data.resolver

import com.narcictub.app.domain.model.MediaInfo
import com.narcictub.app.domain.model.MediaProvider
import com.narcictub.app.domain.resolver.MediaExtractor
import com.narcictub.app.domain.resolver.MediaProviderDetector
import com.narcictub.app.domain.resolver.MediaResolveException
import com.narcictub.app.domain.resolver.MediaResolver
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PHASE 17: the MediaResolver implementation that routes a URL through the
 * extractor registry.
 *
 * Flow: URL → provider detection → first extractor whose [MediaExtractor.supports]
 * returns true → extract(). URLs recognized as known providers (YouTube,
 * Instagram, …) with NO registered extractor fail with a typed
 * [MediaResolveException.UnsupportedProvider] — an honest "recognized, but
 * extraction not implemented yet" result with no network probing and no
 * fabricated metadata. Unrecognized hosts fall through to the direct-media
 * extractor, preserving the exact Phase 8 behavior for direct links.
 *
 * No provider logic ever leaks into ViewModels; adding a future provider
 * means registering one more [MediaExtractor] implementation.
 */
@Singleton
class ExtractorRegistryMediaResolver @Inject constructor(
    private val extractors: Set<@JvmSuppressWildcards MediaExtractor>,
) : MediaResolver {

    // PHASE 18: deterministic selection — descending priority, with the
    // extractor identity as an immutable tie-breaker so the outcome never
    // depends on registration order.
    private val ordered: List<MediaExtractor> =
        extractors.sortedWith(compareByDescending<MediaExtractor> { it.priority }.thenBy { it::class.qualifiedName })

    override suspend fun resolve(url: String): Result<MediaInfo> {
        val extractor = ordered.firstOrNull { it.supports(url) }
        if (extractor != null) return extractor.extract(url)

        val provider = MediaProviderDetector.detect(url)
        if (provider != MediaProvider.UNKNOWN) {
            return Result.failure(MediaResolveException.UnsupportedProvider(provider))
        }
        return Result.failure(
            MediaResolveException.UnsupportedSource(
                "no extractor supports this URL",
            ),
        )
    }
}
