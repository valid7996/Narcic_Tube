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
    /**
     * SAF document-tree URI of a user-picked custom download folder
     * (Settings → Custom folder). Null = follow [downloadLocation]; non-null
     * overrides it — the publisher falls back to [downloadLocation] when the
     * picked folder is no longer accessible (permission revoked, folder
     * removed), so a download never dies because of the override.
     */
    val customDownloadFolderUri: String? = null,
    val wifiOnly: Boolean = true,
    val concurrentDownloads: Int = 3,
    val notificationsEnabled: Boolean = true,
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
