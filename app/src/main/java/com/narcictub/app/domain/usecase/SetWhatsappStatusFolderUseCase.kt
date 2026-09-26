package com.narcictub.app.domain.usecase

import com.narcictub.app.domain.repository.SettingsRepository
import javax.inject.Inject

/** Persists (or clears with null) the WhatsApp statuses folder tree URI. */
class SetWhatsappStatusFolderUseCase @Inject constructor(
    private val repository: SettingsRepository,
) {
    suspend operator fun invoke(uri: String?): Result<Unit> =
        runCatching { repository.setWhatsappStatusFolder(uri) }
}
