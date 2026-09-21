package com.narcictub.app.domain.resolver

import com.narcictub.app.domain.model.MediaInfo

/**
 * Abstraction over metadata extraction for a source URL. Implementations
 * live in the data layer; domain and UI only ever see this contract.
 */
interface MediaResolver {

    /**
     * Resolves [url] into [MediaInfo]. Never throws — every failure is
     * reported through the returned [Result] as a [MediaResolveException]
     * (coroutine cancellation excepted, which propagates as usual).
     */
    suspend fun resolve(url: String): Result<MediaInfo>
}
