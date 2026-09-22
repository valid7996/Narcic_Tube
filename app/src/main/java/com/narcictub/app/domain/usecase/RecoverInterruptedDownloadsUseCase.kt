package com.narcictub.app.domain.usecase

import com.narcictub.app.domain.repository.DownloadRepository
import javax.inject.Inject

/**
 * App-startup recovery of rows stuck in non-terminal states by a previous
 * process death. Idempotent; safe to call once per process start.
 */
class RecoverInterruptedDownloadsUseCase @Inject constructor(
    private val repository: DownloadRepository,
) {
    suspend operator fun invoke(): Int = repository.recoverInterrupted()
}
