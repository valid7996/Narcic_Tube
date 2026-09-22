package com.narcictub.app.domain.usecase

import com.narcictub.app.domain.repository.DownloadRepository
import javax.inject.Inject

/** Bulk removal: [RemoveCompletedDownloadsUseCase] deletes finished records + files. */
class RemoveCompletedDownloadsUseCase @Inject constructor(
    private val repository: DownloadRepository,
) {
    suspend operator fun invoke(): Int = repository.removeCompleted()
}

/** Bulk removal: failed/cancelled records (no files exist for them). */
class RemoveFailedDownloadsUseCase @Inject constructor(
    private val repository: DownloadRepository,
) {
    suspend operator fun invoke(): Int = repository.removeFailed()
}
