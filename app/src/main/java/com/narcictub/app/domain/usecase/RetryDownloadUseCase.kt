package com.narcictub.app.domain.usecase

import com.narcictub.app.domain.repository.DownloadRepository
import javax.inject.Inject

/**
 * Re-queues a failed/cancelled download. Returns the new history id, or
 * null when the row no longer exists or is not retryable (the UI treats
 * null as "nothing happened" — no error surfaced to the user).
 */
class RetryDownloadUseCase @Inject constructor(
    private val repository: DownloadRepository,
) {
    suspend operator fun invoke(id: Long): Long? = repository.retry(id)
}
