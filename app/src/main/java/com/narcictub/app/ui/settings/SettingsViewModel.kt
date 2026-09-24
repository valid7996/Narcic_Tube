package com.narcictub.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.narcictub.app.domain.model.AppSettings
import com.narcictub.app.domain.model.DownloadLocation
import com.narcictub.app.domain.model.ThemeMode
import com.narcictub.app.domain.usecase.ObserveSettingsUseCase
import com.narcictub.app.domain.usecase.SetConcurrentDownloadsUseCase
import com.narcictub.app.domain.usecase.SetCustomDownloadFolderUseCase
import com.narcictub.app.domain.usecase.SetDownloadLocationUseCase
import com.narcictub.app.domain.usecase.SetThemeModeUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * PHASE 13 — immutable UI state for Settings. Values come from the
 * DataStore-backed repository via use cases (single source of truth, no
 * duplicated theme state); errors are safe, user-readable messages — never
 * raw exceptions.
 */
data class SettingsUiState(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val downloadLocation: DownloadLocation = DownloadLocation.DOWNLOADS,
    val customFolderUri: String? = null,
    val concurrentDownloads: Int = AppSettings.MIN_CONCURRENT_DOWNLOADS,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    observeSettings: ObserveSettingsUseCase,
    private val setThemeMode: SetThemeModeUseCase,
    private val setDownloadLocation: SetDownloadLocationUseCase,
    private val setCustomDownloadFolder: SetCustomDownloadFolderUseCase,
    private val setConcurrentDownloads: SetConcurrentDownloadsUseCase,
) : ViewModel() {

    /** One-shot safe message for a failed write; cleared on the next success. */
    private val writeError = MutableStateFlow<String?>(null)

    /** Set when reading the persisted settings failed (screen stays usable). */
    private val loadFailed = MutableStateFlow(false)

    private val loadedState = observeSettings()
        .catch {
            // Corrupt/unreadable DataStore must not crash the screen: fall
            // back to defaults and show a safe message. Raw exceptions are
            // never exposed.
            loadFailed.value = true
            emit(AppSettings())
        }
        .map { settings ->
            SettingsUiState(
                themeMode = settings.theme,
                downloadLocation = settings.downloadLocation,
                customFolderUri = settings.customDownloadFolderUri,
                concurrentDownloads = settings.concurrentDownloads,
                isLoading = false,
            )
        }

    val uiState: StateFlow<SettingsUiState> =
        combine(loadedState, writeError, loadFailed) { state, writeErr, loadErr ->
            state.copy(errorMessage = writeErr ?: if (loadErr) LOAD_ERROR else null)
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            SettingsUiState(),
        )

    /**
     * Theme projection for the app shell (MainActivity) — derived from the
     * SAME repository flow, not a duplicated source of truth.
     */
    val themeMode: StateFlow<ThemeMode> = uiState
        .map { it.themeMode }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeMode.SYSTEM)

    fun onThemeModeSelected(mode: ThemeMode) = launchWrite { setThemeMode(mode) }

    fun onDownloadLocationSelected(location: DownloadLocation) =
        launchWrite { setDownloadLocation(location) }

    /**
     * Persists the picked SAF folder (null clears it). The persistable grant
     * is taken by the screen before this is called.
     */
    fun onCustomFolderSelected(uri: String?) = launchWrite { setCustomDownloadFolder(uri) }

    /** Out-of-range values are rejected by the use case — safe message only. */
    fun onConcurrentDownloadsSelected(count: Int) = launchWrite { setConcurrentDownloads(count) }

    private fun launchWrite(write: suspend () -> Result<Unit>) {
        viewModelScope.launch {
            writeError.value = try {
                write().fold(onSuccess = { null }, onFailure = { WRITE_ERROR })
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                WRITE_ERROR
            }
        }
    }

    private companion object {
        const val LOAD_ERROR = "Settings couldn't be loaded. Showing defaults."
        const val WRITE_ERROR = "Couldn't save that setting. Try again."
    }
}
