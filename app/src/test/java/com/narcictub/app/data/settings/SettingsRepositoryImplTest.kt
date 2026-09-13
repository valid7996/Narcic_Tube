package com.narcictub.app.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.narcictub.app.domain.model.AppSettings
import com.narcictub.app.domain.model.DownloadLocation
import com.narcictub.app.domain.model.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * JVM-only DataStore tests — a real Preferences DataStore on a temp file,
 * fresh instance per test via [newTestStore]. No Robolectric needed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsRepositoryImplTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher + SupervisorJob())

    private fun newRepository(): SettingsRepositoryImpl {
        val file = tmpFolder.newFile("settings_${System.nanoTime()}.preferences_pb")
        val store = PreferenceDataStoreFactory.create(scope = testScope.backgroundScope) { file }
        return SettingsRepositoryImpl(store)
    }

    @After
    fun tearDown() {
        testScope.cancel()
    }

    @Test
    fun `defaults are emitted when nothing is stored`() = testScope.runTest {
        assertEquals(AppSettings(), newRepository().settings.first())
    }

    @Test
    fun `theme round-trips`() = testScope.runTest {
        val repository = newRepository()
        repository.setTheme(ThemeMode.DARK)
        assertEquals(ThemeMode.DARK, repository.settings.first().theme)
    }

    @Test
    fun `download location round-trips`() = testScope.runTest {
        val repository = newRepository()
        repository.setDownloadLocation(DownloadLocation.MUSIC)
        assertEquals(DownloadLocation.MUSIC, repository.settings.first().downloadLocation)
    }

    @Test
    fun `wifi-only round-trips`() = testScope.runTest {
        val repository = newRepository()
        repository.setWifiOnly(false)
        assertFalse(repository.settings.first().wifiOnly)
    }

    @Test
    fun `notifications round-trips`() = testScope.runTest {
        val repository = newRepository()
        repository.setNotificationsEnabled(false)
        assertFalse(repository.settings.first().notificationsEnabled)
    }

    @Test
    fun `concurrent downloads is clamped to the allowed range`() = testScope.runTest {
        val repository = newRepository()
        repository.setConcurrentDownloads(99)
        assertEquals(8, repository.settings.first().concurrentDownloads)
        repository.setConcurrentDownloads(0)
        assertEquals(1, repository.settings.first().concurrentDownloads)
        repository.setConcurrentDownloads(4)
        assertEquals(4, repository.settings.first().concurrentDownloads)
    }

    @Test
    fun `defaults stay in sync with AppSettings`() = testScope.runTest {
        // Pins the "defaults must stay in sync" contract from AppSettings.
        val defaults = newRepository().settings.first()
        assertEquals(ThemeMode.SYSTEM, defaults.theme)
        assertEquals(DownloadLocation.DOWNLOADS, defaults.downloadLocation)
        assertEquals(true, defaults.wifiOnly)
        assertEquals(3, defaults.concurrentDownloads)
        assertEquals(true, defaults.notificationsEnabled)
    }
}
