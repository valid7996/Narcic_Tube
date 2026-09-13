package com.narcictub.app.domain.usecase

import com.narcictub.app.domain.UrlValidator
import com.narcictub.app.domain.repository.DownloadRepository
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

/**
 * Authoritative boundary for starting a download: validates the URL with the
 * domain security policy, then queues it. Invalid URLs NEVER reach the
 * repository or the downloader.
 */
class EnqueueDownloadUseCase @Inject constructor(
    private val repository: DownloadRepository,
) {
    suspend operator fun invoke(url: String): Result<Long> {
        val message = UrlValidator.validationMessage(url)
        if (message != null) {
            return Result.failure(InvalidUrlException(message))
        }
        return try {
            Result.success(repository.enqueue(UrlValidator.normalize(url)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(IllegalStateException("Could not queue the download", e))
        }
    }
}
