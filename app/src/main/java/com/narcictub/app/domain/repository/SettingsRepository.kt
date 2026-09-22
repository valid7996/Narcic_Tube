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

    suspend fun setWifiOnly(enabled: Boolean)

    suspend fun setConcurrentDownloads(count: Int)

    suspend fun setNotificationsEnabled(enabled: Boolean)

    suspend fun setClipboardWatcherEnabled(enabled: Boolean)
}
