package com.narcictub.app.domain.resolver

import com.narcictub.app.domain.model.MediaInfo

/**
 * Abstraction over metadata extraction for a source URL. Implementations
 * live in the data layer; domain and UI only ever see this contract.
 */
interface MediaResolver {

    /**
     * Resolves [url] into [MediaInfo]. Never throws — every failure is
     * reported through the returned [Result].
     */
    suspend fun resolve(url: String): Result<MediaInfo>
}

/** Raised while no real resolver implementation is wired yet. */
class ResolverNotImplementedException(
    message: String = "Media resolver is not implemented yet — no metadata extraction is performed",
) : Exception(message)
