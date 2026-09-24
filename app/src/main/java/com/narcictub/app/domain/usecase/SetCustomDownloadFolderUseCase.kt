package com.narcictub.app.domain.usecase

import com.narcictub.app.domain.repository.SettingsRepository
import javax.inject.Inject

/**
 * Persists the custom download folder (SAF tree URI from the folder picker)
 * or clears it with null. The URI is stored opaquely — no filesystem paths.
 */
class SetCustomDownloadFolderUseCase @Inject constructor(
    private val repository: SettingsRepository,
) {
    suspend operator fun invoke(uri: String?): Result<Unit> =
        runCatching { repository.setCustomDownloadFolder(uri) }
}
