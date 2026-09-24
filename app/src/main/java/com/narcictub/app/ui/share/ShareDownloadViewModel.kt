package com.narcictub.app.ui.share

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.narcictub.app.domain.NetworkDestinationPolicy
import com.narcictub.app.domain.UrlValidator
import com.narcictub.app.domain.model.MediaInfo
import com.narcictub.app.domain.model.MediaProvider
import com.narcictub.app.domain.model.MediaVariant
import com.narcictub.app.domain.usecase.EnqueueDownloadUseCase
import com.narcictub.app.domain.usecase.InvalidUrlException
import com.narcictub.app.domain.usecase.ResolveUrlUseCase
import com.narcictub.app.ui.common.ResolveErrorMessages
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state for the Share download screen ("Download as"): the screen a user
 * lands on after sharing a link (e.g. a YouTube video) into NarcicTub.
 *
 * The shared URL resolves into real format variants (music / video, with
 * real sizes when known); the user picks one and queues the download —
 * nothing is ever downloaded without an explicit tap. All failure states
 * are safe, user-readable messages.
 */
data class ShareDownloadUiState(
    /** The validated shared URL this screen was opened with; null = invalid intake. */
    val url: String? = null,
    val isResolving: Boolean = false,
    val resolvedMedia: MediaInfo? = null,
    /** Identity (downloadUrl) of the selected variant; null = nothing selected. */
    val selectedVariantUrl: String? = null,
    val isEnqueueing: Boolean = false,
    /** One-shot: the selected variant was queued — the UI routes to Downloads. */
    val queued: Boolean = false,
    val errorMessage: String? = null,
) {
    /** The selected variant, resolved by identity against the CURRENT media. */
    val selectedVariant: MediaVariant?
        get() = resolvedMedia?.variants?.firstOrNull { it.downloadUrl == selectedVariantUrl }

    /** A Download tap is meaningful only with a live selection on current media. */
    val canDownload: Boolean
        get() = url != null && !isResolving && !isEnqueueing && selectedVariant != null
}

/**
 * Share-target download flow, mirroring the Home form's Phase 20 variant
 * rules exactly (same guards, same use cases, same safe error mapping):
 *
 *  - the shared URL resolves ONCE when the screen opens (retryable),
 *  - selection is by identity and must belong to the CURRENT media,
 *  - a variant that fails the stage-1 destination policy is never
 *    selectable (the full policy re-runs inside the downloader),
 *  - enqueueing goes through EnqueueDownloadUseCase with the variant's
 *    real duration and the provider's real title.
 */
@HiltViewModel
class ShareDownloadViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val resolveUrl: ResolveUrlUseCase,
    private val enqueueDownload: EnqueueDownloadUseCase,
) : ViewModel() {

    /** Navigation argument key — set by Destination.ShareDownload. */
    private val sharedUrl: String? = savedStateHandle.get<String>(KEY_URL)
        ?.takeIf { UrlValidator.isValidHttpUrl(it) }

    private val _uiState = MutableStateFlow(ShareDownloadUiState(url = sharedUrl))
    val uiState: StateFlow<ShareDownloadUiState> = _uiState.asStateFlow()

    private var resolveJob: Job? = null

    init {
        if (sharedUrl != null) resolve() else showIntakeError()
    }

    /** Authoritative resolve via ResolveUrlUseCase; retryable with [retry]. */
    fun retry() {
        resolveJob?.cancel()
        resolve()
    }

    private fun resolve() {
        val url = sharedUrl
        if (url == null || _uiState.value.isResolving) return
        _uiState.update {
            it.copy(
                isResolving = true,
                errorMessage = null,
                resolvedMedia = null,
                selectedVariantUrl = null,
            )
        }
        resolveJob = viewModelScope.launch {
            val result = resolveUrl(url)
            _uiState.update { state ->
                result.fold(
                    onSuccess = { info ->
                        // Same auto-selection rule as Home: exactly one real
                        // variant auto-selects when it passes the policy.
                        val single = info.variants.singleOrNull()
                        val autoSelected = single?.takeIf { isVariantDownloadable(it) }?.downloadUrl
                        state.copy(
                            isResolving = false,
                            resolvedMedia = info,
                            selectedVariantUrl = autoSelected,
                            errorMessage = if (single != null && autoSelected == null) {
                                "This media's download address was rejected by the security policy."
                            } else {
                                null
                            },
                        )
                    },
                    onFailure = { error ->
                        when (error) {
                            is InvalidUrlException -> state.copy(
                                isResolving = false,
                                errorMessage = error.message,
                            )
                            else -> state.copy(
                                isResolving = false,
                                errorMessage = ResolveErrorMessages.messageFor(error),
                            )
                        }
                    },
                )
            }
        }
    }

    /**
     * Selects one of the CURRENT media's real variants; a foreign URL or a
     * policy-rejected one is refused (never selectable) — same contract as
     * the Home form.
     */
    fun onVariantSelected(variantUrl: String) {
        _uiState.update { state ->
            val variant = state.resolvedMedia?.variants?.firstOrNull {
                it.downloadUrl == variantUrl
            }
            when {
                variant == null -> state
                !isVariantDownloadable(variant) -> state.copy(
                    errorMessage = "This variant's download address was rejected by the security policy.",
                )
                else -> state.copy(selectedVariantUrl = variantUrl, errorMessage = null)
            }
        }
    }

    /** Stage-1 destination policy + URL structure check (full policy re-runs in the downloader). */
    private fun isVariantDownloadable(variant: MediaVariant): Boolean =
        UrlValidator.isValidHttpUrl(variant.downloadUrl) &&
            NetworkDestinationPolicy.isAllowed(variant.downloadUrl)

    /**
     * Enqueues the SELECTED variant — with the same stale-selection guard as
     * the Home form: a selection that no longer belongs to the current media
     * produces a typed error, never an enqueue.
     */
    fun onDownloadSelected() {
        val state = _uiState.value
        if (state.isEnqueueing || !state.canDownload) return

        val variant = state.selectedVariant
        if (variant == null) {
            _uiState.update {
                it.copy(
                    errorMessage = "That selection is no longer available. Try again.",
                    selectedVariantUrl = null,
                )
            }
            return
        }

        _uiState.update { it.copy(isEnqueueing = true, errorMessage = null) }
        viewModelScope.launch {
            // The provider's real title and the variant's real duration
            // travel with the row (same as the Home form).
            val providerTitle = state.resolvedMedia
                ?.takeIf { it.provider != MediaProvider.UNKNOWN }
                ?.title
            val result = enqueueDownload(variant.downloadUrl, variant.durationSeconds, providerTitle)
            _uiState.update { current ->
                result.fold(
                    onSuccess = { current.copy(isEnqueueing = false, queued = true) },
                    onFailure = { error ->
                        when (error) {
                            is InvalidUrlException -> current.copy(
                                isEnqueueing = false,
                                errorMessage = error.message,
                            )
                            else -> current.copy(
                                isEnqueueing = false,
                                errorMessage = "Couldn't queue the download.",
                            )
                        }
                    },
                )
            }
        }
    }

    private fun showIntakeError() {
        _uiState.update {
            it.copy(errorMessage = "The shared text doesn't contain a supported link.")
        }
    }

    private companion object {
        const val KEY_URL = "url"
    }
}
