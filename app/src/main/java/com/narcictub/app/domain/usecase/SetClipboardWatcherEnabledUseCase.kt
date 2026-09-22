package com.narcictub.app.domain.usecase

import com.narcictub.app.domain.repository.SettingsRepository
import javax.inject.Inject

/** Persists whether the background clipboard-link watcher is enabled. */
class SetClipboardWatcherEnabledUseCase @Inject constructor(
    private val repository: SettingsRepository,
) {
    suspend operator fun invoke(enabled: Boolean): Result<Unit> =
        runCatching { repository.setClipboardWatcherEnabled(enabled) }
}
