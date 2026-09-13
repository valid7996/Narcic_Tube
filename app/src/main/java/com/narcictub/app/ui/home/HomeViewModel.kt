package com.narcictub.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.narcictub.app.domain.UrlValidator
import com.narcictub.app.domain.resolver.ResolverNotImplementedException
import com.narcictub.app.domain.usecase.EnqueueDownloadUseCase
import com.narcictub.app.domain.usecase.InvalidUrlException
import com.narcictub.app.domain.usecase.ResolveUrlUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Home form logic. ARCH-1 FIX (Phase 6): the resolve/download path now goes
 * through the domain use cases —
 *   onUrlChange → UrlValidator (lightweight UX feedback only)
 *   onResolve   → ResolveUrlUseCase (authoritative boundary)
 *   onDownload  → EnqueueDownloadUseCase (authoritative boundary)
 * The ViewModel performs NO independent business validation on the action
 * paths; the live-typing check is UX-only and duplicates no policy (the
 * use cases re-validate regardless of what the UI enabled).
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val resolveUrl: ResolveUrlUseCase,
    private val enqueueDownload: EnqueueDownloadUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    fun onUrlChange(newUrl: String) {
        // UX-only lightweight feedback; NOT a security boundary.
        val message = UrlValidator.validationMessage(newUrl)
            ?.takeUnless { it == "Paste a link to begin" }
        _uiState.update {
            it.copy(
                url = newUrl,
                isUrlValid = newUrl.isNotBlank() && message == null,
                validationMessage = message?.takeIf { newUrl.isNotBlank() },
                resolvedHost = null,
                errorMessage = null,
            )
        }
    }

    fun onClear() {
        _uiState.update { HomeUiState() }
    }

    /** Authoritative resolve via ResolveUrlUseCase. */
    fun onResolve() {
        if (_uiState.value.isResolving) return
        _uiState.update { it.copy(isResolving = true, errorMessage = null) }
        viewModelScope.launch {
            val result = resolveUrl(_uiState.value.url)
            _uiState.update { state ->
                result.fold(
                    onSuccess = { info ->
                        // Stub resolver never reaches here today; a real
                        // resolver will. Host display only — no fabricated
                        // metadata beyond what MediaInfo actually carries.
                        state.copy(isResolving = false, resolvedHost = info.host)
                    },
                    onFailure = { error ->
                        when (error) {
                            is InvalidUrlException -> state.copy(
                                isResolving = false,
                                isUrlValid = false,
                                validationMessage = error.message,
                                resolvedHost = null,
                            )
                            is ResolverNotImplementedException -> state.copy(
                                isResolving = false,
                                errorMessage = "Metadata extraction isn't available yet — you can still download the file directly.",
                            )
                            else -> state.copy(
                                isResolving = false,
                                errorMessage = "Couldn't resolve that link.",
                            )
                        }
                    },
                )
            }
        }
    }

    /** Queues the download via EnqueueDownloadUseCase. */
    fun onDownload() {
        if (_uiState.value.isDownloading) return
        _uiState.update { it.copy(isDownloading = true, errorMessage = null) }
        viewModelScope.launch {
            val result = enqueueDownload(_uiState.value.url)
            _uiState.update { state ->
                result.fold(
                    onSuccess = { state.copy(isDownloading = false, queuedSuccessfully = true) },
                    onFailure = { error ->
                        when (error) {
                            is InvalidUrlException -> state.copy(
                                isDownloading = false,
                                isUrlValid = false,
                                validationMessage = error.message,
                            )
                            else -> state.copy(
                                isDownloading = false,
                                errorMessage = "Couldn't queue the download.",
                            )
                        }
                    },
                )
            }
        }
    }

    /** Resets the one-shot "queued" toast/scaffold flag after UI consumed it. */
    fun onQueuedMessageShown() {
        _uiState.update { it.copy(queuedSuccessfully = false) }
    }
}
