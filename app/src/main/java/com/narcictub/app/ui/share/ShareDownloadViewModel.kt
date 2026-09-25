package com.narcictub.app.ui.share

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.narcictub.app.domain.NetworkDestinationPolicy
import com.narcictub.app.domain.UrlValidator
import com.narcictub.app.domain.model.MediaInfo
import com.narcictub.app.domain.model.MediaProvider
import com.narcictub.app.domain.model.MediaVariant
import com.narcictub.app.domain.share.SharedTextUrl
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
 * UI state for the compact Share download sheet ("Download as") — the small
 * floating dialog that appears OVER the sharing app (YouTube, Instagram, …)
 * after Share → NarcicTub.
 *
 * The shared URL resolves into real format variants (music / video, with
 * real sizes when known); the user picks one and queues the download —
 * nothing is ever downloaded without an explicit tap. All failure states
 * are safe, user-readable messages.
 */
data class ShareDownloadUiState(
    /** The validated shared URL this sheet was opened with; null = invalid intake. */
    val url: String? = null,
    val isResolving: Boolean = false,
    val resolvedMedia: MediaInfo? = null,
    /** Identity (downloadUrl) of the selected variant; null = nothing selected. */
    val selectedVariantUrl: String? = null,
    val isEnqueueing: Boolean = false,
    /** One-shot: the selected variant was queued — show the confirmation row. */
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
 *  - the shared TEXT is extracted and validated once, then the URL resolves
 *    ONCE (retryable),
 *  - selection is by identity and must belong to the CURRENT media,
 *  - a variant that fails the stage-1 destination policy is never
 *    selectable (the full policy re-runs inside the downloader),
 *  - enqueueing goes through EnqueueDownloadUseCase with the variant's
 *    real duration and the provider's real title; the download then runs
 *    in the app-scoped worker scope and survives this dialog closing.
 */
@HiltViewModel
class ShareDownloadViewModel @Inject constructor(
    private val resolveUrl: ResolveUrlUseCase,
    private val enqueueDownload: EnqueueDownloadUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ShareDownloadUiState())
    val uiState: StateFlow<ShareDownloadUiState> = _uiState.asStateFlow()

    private var resolveJob: Job? = null

    /**
     * Entry point for the share-target activity: raw ACTION_SEND text.
     * Called once per fresh activity creation (recreation keeps the state).
     */
    fun onSharedText(rawText: String?) {
        resolveJob?.cancel()
        resolveJob = null
        val reset = {
            _uiState.update {
                it.copy(
                    url = null,
                    isResolving = false,
                    resolvedMedia = null,
                    selectedVariantUrl = null,
                    queued = false,
                )
            }
        }
        when (val extraction = SharedTextUrl.extract(rawText)) {
            is SharedTextUrl.Extraction.Single -> start(extraction.url)
            is SharedTextUrl.Extraction.Ambiguous -> {
                reset()
                _uiState.update {
                    it.copy(
                        errorMessage = "The shared text contains ${extraction.candidates} " +
                            "different links. Copy the one you want and paste it directly.",
                    )
                }
            }
            SharedTextUrl.Extraction.None -> {
                reset()
                _uiState.update {
                    it.copy(errorMessage = "The shared text doesn't contain a supported link.")
                }
            }
        }
    }

    /** Authoritative resolve via ResolveUrlUseCase; retryable with [retry]. */
    fun retry() {
        resolveJob?.cancel()
        resolve()
    }

    private fun start(url: String) {
        _uiState.update { it.copy(url = url) }
        resolve()
    }

    private fun resolve() {
        val url = _uiState.value.url ?: return
        if (_uiState.value.isResolving) return
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
            // travel with the row (same as the Home form). The enqueue lives
            // in the app-scoped repository/worker scope — closing this dialog
            // (or the whole task) never cancels the download.
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
}
