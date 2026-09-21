package com.narcictub.app.domain.usecase

import com.narcictub.app.domain.model.ThemeMode
import com.narcictub.app.domain.repository.SettingsRepository
import javax.inject.Inject

/** PHASE 13: persists the user's theme choice. */
class SetThemeModeUseCase @Inject constructor(
    private val repository: SettingsRepository,
) {
    suspend operator fun invoke(mode: ThemeMode): Result<Unit> =
        runCatching { repository.setTheme(mode) }
}
