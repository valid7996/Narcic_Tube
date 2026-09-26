package com.narcictub.app.domain.repository

import com.narcictub.app.domain.model.AppSettings
import com.narcictub.app.domain.model.DownloadLocation
import com.narcictub.app.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow

/** Typed settings contract. No secrets may be stored through this API. */
interface SettingsRepository {

    val settings: Flow<AppSettings>

    suspend fun setTheme(mode: ThemeMode)

    suspend fun setDownloadLocation(location: DownloadLocation)

    /**
     * Persists the custom download folder (SAF tree URI) or clears it with
     * null. The URI string is opaque — produced only by the folder picker.
     */
    suspend fun setCustomDownloadFolder(uri: String?)

    /** Persists (or clears with null) the WhatsApp statuses folder tree URI. */
    suspend fun setWhatsappStatusFolder(uri: String?)

    suspend fun setWifiOnly(enabled: Boolean)

    suspend fun setConcurrentDownloads(count: Int)

    suspend fun setNotificationsEnabled(enabled: Boolean)
}
