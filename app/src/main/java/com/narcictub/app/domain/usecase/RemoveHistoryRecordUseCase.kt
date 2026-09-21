package com.narcictub.app.domain.usecase

import com.narcictub.app.domain.repository.DownloadRepository
import javax.inject.Inject

/**
 * PHASE 10: "remove from history" — deletes ONLY the record for a terminal
 * row (COMPLETED/FAILED/CANCELLED). The published file, if any, is NEVER
 * touched; deleting media is a separate, explicitly confirmed action
 * ([RemoveDownloadUseCase]). False means not removable (missing or still
 * in flight).
 */
class RemoveHistoryRecordUseCase @Inject constructor(
    private val repository: DownloadRepository,
) {
    suspend operator fun invoke(id: Long): Boolean = repository.removeRecord(id)
}
