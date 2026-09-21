package com.narcictub.app.domain.usecase

import com.narcictub.app.domain.model.DownloadLocation
import com.narcictub.app.domain.repository.SettingsRepository
import javax.inject.Inject

/** PHASE 13: persists the download location (enum — no filesystem paths). */
class SetDownloadLocationUseCase @Inject constructor(
    private val repository: SettingsRepository,
) {
    suspend operator fun invoke(location: DownloadLocation): Result<Unit> =
        runCatching { repository.setDownloadLocation(location) }
}
