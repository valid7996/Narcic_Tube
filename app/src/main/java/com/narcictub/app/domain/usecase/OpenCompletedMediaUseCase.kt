package com.narcictub.app.domain.usecase

import com.narcictub.app.domain.model.OpenableMedia
import com.narcictub.app.domain.repository.DownloadRepository
import javax.inject.Inject

/**
 * PHASE 10: authoritative boundary for opening a completed download's file.
 * Returns validated, availability-checked [OpenableMedia] — or null when the
 * record is not completed, the file no longer resolves, or the stored URI
 * fails the safety policy. The UI launches the standard ACTION_VIEW intent
 * from this result and never constructs a target itself.
 */
class OpenCompletedMediaUseCase @Inject constructor(
    private val repository: DownloadRepository,
) {
    suspend operator fun invoke(id: Long): OpenableMedia? = repository.openableMedia(id)
}
