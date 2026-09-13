package com.narcictub.app.domain.model

/** User-selectable theme behavior. */
enum class ThemeMode { SYSTEM, DARK, LIGHT }

/** Where finished downloads are written. */
enum class DownloadLocation { DOWNLOADS, MUSIC, MOVIES, DCIM }

/**
 * Typed app settings exposed by SettingsRepository. Defaults must stay in
 * sync with SettingsRepositoryImpl's fallbacks (pinned by tests).
 */
data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val downloadLocation: DownloadLocation = DownloadLocation.DOWNLOADS,
    val wifiOnly: Boolean = true,
    val concurrentDownloads: Int = 3,
    val notificationsEnabled: Boolean = true,
)
