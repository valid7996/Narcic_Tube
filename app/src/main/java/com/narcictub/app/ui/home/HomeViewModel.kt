package com.narcictub.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.narcictub.app.domain.UrlValidator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.URI
import javax.inject.Inject

/**
 * Home form logic: URL editing + validation + a stub "resolve" step.
 *
 * The resolve step only extracts the host and reports readiness — real
 * metadata fetching lands with the data layer (later phase) and will replace
 * [onResolve] internals without changing this state contract.
 */
@HiltViewModel
class HomeViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    fun onUrlChange(newUrl: String) {
        val normalized = UrlValidator.normalize(newUrl)
        val message = if (normalized.isEmpty()) {
            null
        } else {
            UrlValidator.validationMessage(normalized)
        }
        _uiState.update {
            it.copy(
                url = newUrl,
                isUrlValid = message == null && normalized.isNotEmpty(),
                validationMessage = message?.takeIf { normalized.isNotEmpty() },
                // Typing invalidates any previous resolve result.
                resolvedHost = null,
                errorMessage = null,
            )
        }
    }

    fun onClear() {
        _uiState.update { HomeUiState() }
    }

    fun onResolve() {
        val current = _uiState.value
        val normalized = UrlValidator.normalize(current.url)
        val message = UrlValidator.validationMessage(normalized)
        if (message != null) {
            _uiState.update {
                it.copy(
                    isUrlValid = false,
                    validationMessage = message,
                    resolvedHost = null,
                )
            }
            return
        }
        if (current.isResolving) return
        _uiState.update { it.copy(isResolving = true, errorMessage = null) }
        viewModelScope.launch {
            delay(400)
            val host = try {
                URI(normalized).host ?: normalized
            } catch (_: Exception) {
                normalized
            }
            _uiState.update {
                it.copy(isResolving = false, resolvedHost = host)
            }
        }
    }
}
