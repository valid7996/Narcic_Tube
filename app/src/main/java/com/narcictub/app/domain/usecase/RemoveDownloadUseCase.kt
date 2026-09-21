package com.narcictub.app.domain.usecase

import com.narcictub.app.domain.repository.DownloadRepository
import javax.inject.Inject

/**
 * Removes one finished record. False means "not removable right now" —
 * in-flight rows must be cancelled first; missing rows are already gone.
 */
class RemoveDownloadUseCase @Inject constructor(
    private val repository: DownloadRepository,
) {
    suspend operator fun invoke(id: Long): Boolean = repository.remove(id)
}
