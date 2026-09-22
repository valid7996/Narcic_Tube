package com.narcictub.app.domain.usecase

import com.narcictub.app.domain.repository.DownloadRepository
import javax.inject.Inject

/** Resumes a paused download by history id, continuing from where it left off. */
class ResumeDownloadUseCase @Inject constructor(
    private val repository: DownloadRepository,
) {
    suspend operator fun invoke(id: Long) = repository.resume(id)
}
