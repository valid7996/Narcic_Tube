package com.narcictub.app.domain.usecase

import com.narcictub.app.domain.repository.SettingsRepository
import javax.inject.Inject

/** Persists (or clears with null) the DNS-over-HTTPS endpoint. */
class SetDohUrlUseCase @Inject constructor(
    private val repository: SettingsRepository,
) {
    suspend operator fun invoke(url: String?): Result<Unit> =
        runCatching { repository.setDohUrl(url) }
}
