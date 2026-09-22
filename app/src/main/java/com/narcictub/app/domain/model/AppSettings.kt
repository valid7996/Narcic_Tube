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
    /**
     * Background link detection: a foreground service watches the clipboard
     * and, on YouTube/Instagram links, opens the floating download bubble
     * without bringing the app to the foreground. Off by default — it needs
     * the "display over other apps" permission and runs a foreground
     * service with an ongoing notification, so the user opts in explicitly.
     */
    val clipboardWatcherEnabled: Boolean = false,
) {
    companion object {
        /**
         * PHASE 13: single source of truth for the valid concurrent-downloads
         * range — the use case gates it and the DataStore layer clamps it
         * (both pinned by tests).
         */
        const val MIN_CONCURRENT_DOWNLOADS = 1
        const val MAX_CONCURRENT_DOWNLOADS = 8
    }
}
