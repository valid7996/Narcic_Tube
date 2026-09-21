package com.narcictub.app.domain.usecase

import com.narcictub.app.domain.NetworkDestinationPolicy
import com.narcictub.app.domain.UrlValidator
import com.narcictub.app.domain.repository.DownloadRepository
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

/**
 * Authoritative boundary for starting a download: validates the URL with
 * the domain security policy, then queues it. Invalid URLs NEVER reach the
 * repository or the downloader.
 *
 * PHASE 20 (variant download integration): the queue-time gate now also
 * applies the stage-1 NetworkDestinationPolicy check (scheme, userinfo,
 * localhost/private/metadata literals and host forms) BEFORE queueing, so
 * a variant URL from the resolver is rejected here with a typed failure
 * instead of surfacing later as a failed download row. The FULL policy —
 * including the resolve-all-addresses stage — still runs inside the
 * policy-gated downloader before every connection; this is defense in
 * depth, not a replacement.
 */
class EnqueueDownloadUseCase @Inject constructor(
    private val repository: DownloadRepository,
) {
    suspend operator fun invoke(url: String, durationSeconds: Long? = null): Result<Long> {
        val message = UrlValidator.validationMessage(url)
        if (message != null) {
            return Result.failure(InvalidUrlException(message))
        }
        // Policy runs on the NORMALIZED value — exactly what gets queued.
        val normalized = UrlValidator.normalize(url)
        val policyReason = NetworkDestinationPolicy.disallowedReason(normalized)
        if (policyReason != null) {
            return Result.failure(
                InvalidUrlException("This link points to a blocked destination and can't be used."),
            )
        }
        return try {
            Result.success(repository.enqueue(normalized, durationSeconds))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(IllegalStateException("Could not queue the download", e))
        }
    }
}
