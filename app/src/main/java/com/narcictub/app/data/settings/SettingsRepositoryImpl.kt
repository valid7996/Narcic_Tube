package com.narcictub.app.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.narcictub.app.domain.model.AppSettings
import com.narcictub.app.domain.model.DownloadLocation
import com.narcictub.app.domain.model.ThemeMode
import com.narcictub.app.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** App-scoped Preferences DataStore — created once via this delegate. */
val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "narcictub_settings",
)

/** Persisted enum names. Kept private so nothing writes raw strings by hand. */
private object SettingsKeys {
    val THEME = stringPreferencesKey("theme")
    val DOWNLOAD_LOCATION = stringPreferencesKey("download_location")
    val WIFI_ONLY = booleanPreferencesKey("wifi_only")
    val CONCURRENT_DOWNLOADS = intPreferencesKey("concurrent_downloads")
    val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
}

/**
 * DataStore-backed SettingsRepository. Stores only app preferences — never
 * secrets, credentials, or user content. The DataStore instance is injected
 * (production: the app-scoped delegate; tests: a fresh store per test), so
 * the class itself is JVM-pure.
 */
@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepository {

    override val settings: Flow<AppSettings> = dataStore.data
        .catch { throwable ->
            // A corrupt file must not crash the app forever — fall back to
            // defaults rather than rethrowing.
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }
        .map { prefs ->
            AppSettings(
                theme = prefs[SettingsKeys.THEME]
                    ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                    ?: ThemeMode.SYSTEM,
                downloadLocation = prefs[SettingsKeys.DOWNLOAD_LOCATION]
                    ?.let { runCatching { DownloadLocation.valueOf(it) }.getOrNull() }
                    ?: DownloadLocation.DOWNLOADS,
                wifiOnly = prefs[SettingsKeys.WIFI_ONLY] ?: true,
                concurrentDownloads = (prefs[SettingsKeys.CONCURRENT_DOWNLOADS] ?: 3)
                    .coerceIn(MIN_CONCURRENT, MAX_CONCURRENT),
                notificationsEnabled = prefs[SettingsKeys.NOTIFICATIONS_ENABLED] ?: true,
            )
        }

    override suspend fun setTheme(mode: ThemeMode) {
        dataStore.edit { it[SettingsKeys.THEME] = mode.name }
    }

    override suspend fun setDownloadLocation(location: DownloadLocation) {
        dataStore.edit { it[SettingsKeys.DOWNLOAD_LOCATION] = location.name }
    }

    override suspend fun setWifiOnly(enabled: Boolean) {
        dataStore.edit { it[SettingsKeys.WIFI_ONLY] = enabled }
    }

    override suspend fun setConcurrentDownloads(count: Int) {
        dataStore.edit {
            it[SettingsKeys.CONCURRENT_DOWNLOADS] = count.coerceIn(MIN_CONCURRENT, MAX_CONCURRENT)
        }
    }

    override suspend fun setNotificationsEnabled(enabled: Boolean) {
        dataStore.edit { it[SettingsKeys.NOTIFICATIONS_ENABLED] = enabled }
    }

    companion object {
        // Single source of truth lives in AppSettings (PHASE 13).
        const val MIN_CONCURRENT = AppSettings.MIN_CONCURRENT_DOWNLOADS
        const val MAX_CONCURRENT = AppSettings.MAX_CONCURRENT_DOWNLOADS
    }
}
