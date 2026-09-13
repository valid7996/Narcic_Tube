package com.narcictub.app.data.resolver

import com.narcictub.app.domain.model.MediaInfo
import com.narcictub.app.domain.resolver.MediaResolver
import com.narcictub.app.domain.resolver.ResolverNotImplementedException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stub resolver wiring. There is NO real metadata extraction yet — calling
 * resolve() fails explicitly with ResolverNotImplementedException. It never
 * fabricates titles, durations, progress, or thumbnails, and it performs no
 * network request. Replaced wholesale when the real extractor phase lands.
 */
@Singleton
class StubMediaResolver @Inject constructor() : MediaResolver {

    override suspend fun resolve(url: String): Result<MediaInfo> =
        Result.failure(ResolverNotImplementedException())
}
