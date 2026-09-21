package com.narcictub.app.domain.usecase

import com.narcictub.app.domain.model.AppSettings
import com.narcictub.app.domain.repository.SettingsRepository
import javax.inject.Inject

/**
 * PHASE 13: persists the concurrent-downloads limit. Values outside the
 * valid range [AppSettings.MIN_CONCURRENT_DOWNLOADS]..
 * [AppSettings.MAX_CONCURRENT_DOWNLOADS] are REJECTED here and never reach
 * the repository/DataStore (the repository additionally clamps as defense
 * in depth — both behaviors pinned by tests).
 */
class SetConcurrentDownloadsUseCase @Inject constructor(
    private val repository: SettingsRepository,
) {
    suspend operator fun invoke(count: Int): Result<Unit> {
        if (count !in AppSettings.MIN_CONCURRENT_DOWNLOADS..AppSettings.MAX_CONCURRENT_DOWNLOADS) {
            return Result.failure(IllegalArgumentException("value out of range"))
        }
        return runCatching { repository.setConcurrentDownloads(count) }
    }
}
