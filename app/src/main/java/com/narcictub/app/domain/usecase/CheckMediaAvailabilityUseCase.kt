package com.narcictub.app.domain.usecase

import com.narcictub.app.domain.model.MediaFileAvailability
import com.narcictub.app.domain.repository.DownloadRepository
import javax.inject.Inject

/**
 * PHASE 10: asks the repository whether a completed record's published file
 * still resolves. The result is the backend's honest answer — AVAILABLE,
 * UNAVAILABLE, or NOT_APPLICABLE — never a guess.
 */
class CheckMediaAvailabilityUseCase @Inject constructor(
    private val repository: DownloadRepository,
) {
    suspend operator fun invoke(id: Long): MediaFileAvailability =
        repository.mediaAvailability(id)
}
