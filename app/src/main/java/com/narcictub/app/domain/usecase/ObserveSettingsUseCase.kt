package com.narcictub.app.domain.usecase

import com.narcictub.app.domain.model.AppSettings
import com.narcictub.app.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * PHASE 13: streams the persisted settings to the UI. Single source of
 * truth is the DataStore-backed repository — no duplicated state.
 */
class ObserveSettingsUseCase @Inject constructor(
    private val repository: SettingsRepository,
) {
    operator fun invoke(): Flow<AppSettings> = repository.settings
}
