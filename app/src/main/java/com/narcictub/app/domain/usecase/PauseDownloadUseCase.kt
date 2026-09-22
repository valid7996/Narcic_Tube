package com.narcictub.app.domain.usecase

import com.narcictub.app.domain.repository.DownloadRepository
import javax.inject.Inject

/**
 * Pauses a queued or active download by history id — the bytes already
 * transferred are kept on disk so [ResumeDownloadUseCase] can continue
 * instead of starting the whole download over.
 */
class PauseDownloadUseCase @Inject constructor(
    private val repository: DownloadRepository,
) {
    suspend operator fun invoke(id: Long) = repository.pause(id)
}
