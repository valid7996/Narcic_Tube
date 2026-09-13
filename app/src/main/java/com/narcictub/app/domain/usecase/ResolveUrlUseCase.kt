package com.narcictub.app.domain.usecase

import com.narcictub.app.domain.UrlValidator
import com.narcictub.app.domain.model.MediaInfo
import com.narcictub.app.domain.resolver.MediaResolver
import javax.inject.Inject

/** Rejected before the resolver is ever consulted. */
class InvalidUrlException(message: String) : Exception(message)

/**
 * Validates a user-supplied URL (including the embedded-credential policy)
 * and only then hands the normalized URL to [MediaResolver]. UI must call
 * this — never the resolver directly.
 */
class ResolveUrlUseCase @Inject constructor(
    private val resolver: MediaResolver,
) {
    suspend operator fun invoke(url: String): Result<MediaInfo> {
        val message = UrlValidator.validationMessage(url)
        if (message != null) {
            return Result.failure(InvalidUrlException(message))
        }
        return resolver.resolve(UrlValidator.normalize(url))
    }
}
