package com.narcictub.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.narcictub.app.domain.NetworkDestinationPolicy
import com.narcictub.app.domain.UrlValidator
import com.narcictub.app.domain.model.MediaProvider
import com.narcictub.app.domain.model.MediaVariant
import com.narcictub.app.domain.resolver.MediaResolveException
import com.narcictub.app.domain.usecase.EnqueueDownloadUseCase
import com.narcictub.app.domain.usecase.InvalidUrlException
import com.narcictub.app.domain.share.SharedTextUrl
import com.narcictub.app.domain.usecase.ResolveUrlUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Home form logic (Phase 8). Resolve goes through ResolveUrlUseCase →
 * MediaResolver; queueing through EnqueueDownloadUseCase → the existing
 * Phase 7 download pipeline. The ViewModel performs NO independent business
 * validation on the action paths; the live-typing check is UX-only.
 *
 * Resolve behaviour: real MediaInfo on success, a SAFE mapped error message
 * on failure (never raw exception text, URLs or stack traces). A resolve
 * request is ignored while one is already running (duplicate protection) and
 * is cancelled by onClear; ViewModel teardown cancels it via viewModelScope.
 * There is no fake delay, fake progress or simulated resolution anywhere.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val resolveUrl: ResolveUrlUseCase,
    private val enqueueDownload: EnqueueDownloadUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var resolveJob: Job? = null

    /** Debounces auto-resolve so a link is resolved once typing/pasting settles. */
    private var autoResolveJob: Job? = null

    fun onUrlChange(newUrl: String) {
        // UX-only lightweight feedback; NOT a security boundary.
        val message = UrlValidator.validationMessage(newUrl)
            ?.takeUnless { it == "Paste a link to begin" }
        val isValid = newUrl.isNotBlank() && message == null
        _uiState.update {
            it.copy(
                url = newUrl,
                isUrlValid = isValid,
                validationMessage = message?.takeIf { newUrl.isNotBlank() },
                resolvedMedia = null,
                // Phase 20: a new URL invalidates any previous variant
                // selection (stale-selection protection).
                selectedVariantUrl = null,
                errorMessage = null,
            )
        }
        // Smart link detection: the user should never have to press Resolve
        // by hand. A short debounce lets fast typing/pasting settle first —
        // it restarts on every keystroke, so only the URL the user actually
        // stops on gets resolved. Explicit Resolve stays available as a
        // manual retry.
        autoResolveJob?.cancel()
        if (isValid) {
            autoResolveJob = viewModelScope.launch {
                delay(AUTO_RESOLVE_DEBOUNCE_MS)
                // Skip if: the field changed again since (a newer debounce
                // owns it), a resolve is already running, or this exact URL
                // was already resolved (success OR failure) by a manual
                // Resolve tap that beat the debounce — onResolve() keeps its
                // own duplicate-request guard too, this just avoids
                // needlessly re-resolving a URL that didn't change.
                val current = _uiState.value
                if (current.url == newUrl && !current.isResolving &&
                    current.resolvedMedia == null && current.errorMessage == null
                ) {
                    onResolve()
                }
            }
        }
    }

    fun onClear() {
        resolveJob?.cancel()
        resolveJob = null
        autoResolveJob?.cancel()
        autoResolveJob = null
        _uiState.update { HomeUiState() }
    }

    /** Authoritative resolve via ResolveUrlUseCase. */
    fun onResolve() {
        if (_uiState.value.isResolving) return // duplicate-request protection
        _uiState.update {
            it.copy(
                isResolving = true,
                errorMessage = null,
                resolvedMedia = null,
                // Any previous selection belongs to the previous media.
                selectedVariantUrl = null,
            )
        }
        resolveJob = viewModelScope.launch {
            val result = resolveUrl(_uiState.value.url)
            _uiState.update { state ->
                result.fold(
                    onSuccess = { info ->
                        // Phase 20: exactly one real variant is auto-selected;
                        // multiple require an explicit user choice; none stays
                        // unselected (honest "no variants" state). A single
                        // variant that fails the destination policy is never
                        // auto-selected — the typed error explains why.
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
                                isUrlValid = false,
                                validationMessage = error.message,
                                resolvedMedia = null,
                                selectedVariantUrl = null,
                            )
                            else -> state.copy(
                                isResolving = false,
                                resolvedMedia = null,
                                selectedVariantUrl = null,
                                errorMessage = messageFor(error),
                            )
                        }
                    },
                )
            }
        }
    }

    /**
     * Phase 20: selects one of the CURRENT media's real variants. A URL
     * that does not belong to the resolved media — or that fails the
     * destination policy — is refused (it is never selectable), so the
     * Download gate only ever opens for validated current variants.
     */
    fun onVariantSelected(variantUrl: String) {
        _uiState.update { state ->
            val variant = state.resolvedMedia?.variants?.firstOrNull {
                it.downloadUrl == variantUrl
            }
            when {
                variant == null -> state // not a variant of the current media — ignore
                !isVariantDownloadable(variant) -> state.copy(
                    errorMessage = "This variant's download address was rejected by the security policy.",
                )
                else -> state.copy(selectedVariantUrl = variantUrl, errorMessage = null)
            }
        }
    }

    /**
     * Stage-1 destination policy + URL structure check for a variant URL.
     * The FULL policy (DNS stage) re-runs inside the policy-gated downloader
     * before every connection; this is the early, UI-facing layer.
     */
    private fun isVariantDownloadable(variant: MediaVariant): Boolean =
        UrlValidator.isValidHttpUrl(variant.downloadUrl) &&
            NetworkDestinationPolicy.isAllowed(variant.downloadUrl)

    /**
     * Phase 20: enqueues the SELECTED variant of the CURRENT media through
     * the existing EnqueueDownloadUseCase → download pipeline. The variant
     * identity is re-checked against the current media right here (Case E:
     * a stale/orphaned selection produces a typed error, never an enqueue),
     * and the use case + policy-gated downloader validate the URL again.
     */
    fun onDownload() {
        val state = _uiState.value
        if (state.isDownloading) return // duplicate-submit protection (Case B)
        if (!state.canDownload) return // no current media / no valid selection

        // Explicit stale-selection guard: the identity was set, but it no
        // longer belongs to the current media (structurally hard to reach —
        // selection is cleared on every media change — but never trusted).
        val variant = state.selectedVariant
        if (variant == null) {
            _uiState.update {
                it.copy(
                    errorMessage = "That selection is no longer available. Resolve the media again.",
                    selectedVariantUrl = null,
                )
            }
            return
        }

        _uiState.update { it.copy(isDownloading = true, errorMessage = null) }
        viewModelScope.launch {
            // PHASE 22: the selected variant's real duration persists with
            // the download row so the library can show it honestly.
            // Provider media (YouTube/Instagram) has no file name in its URL, so
            // the resolver's real title travels with the download. Direct links
            // keep the exact previous call (no title).
            val providerTitle = state.resolvedMedia
                ?.takeIf { it.provider != MediaProvider.UNKNOWN }
                ?.title
            val result = enqueueDownload(variant.downloadUrl, variant.durationSeconds, providerTitle)
            _uiState.update { current ->
                result.fold(
                    onSuccess = { current.copy(isDownloading = false, queuedSuccessfully = true) },
                    onFailure = { error ->
                        when (error) {
                            is InvalidUrlException -> current.copy(
                                isDownloading = false,
                                isUrlValid = false,
                                validationMessage = error.message,
                            )
                            else -> current.copy(
                                isDownloading = false,
                                errorMessage = "Couldn't queue the download.",
                            )
                        }
                    },
                )
            }
        }
    }

    /** Shows a safe rejection message for a share with no usable link. */
    fun onShareRejected(message: String) {
        _uiState.update { it.copy(errorMessage = message) }
    }

    /**
     * In-app clipboard suggestion: called whenever the Home screen comes to
     * the foreground (it's the only moment the app may read the clipboard
     * at all). The same conservative single-URL extraction as Android Share
     * intake is reused — never a looser heuristic. A clip already looked at
     * (accepted, dismissed, or simply seen) is never re-suggested, and a
     * clip that matches the URL already in the field is not offered either.
     */
    fun onClipboardTextObserved(rawClipboardText: String?) {
        val state = _uiState.value
        if (rawClipboardText.isNullOrBlank() || rawClipboardText == state.lastSeenClipboardText) return
        val candidate = (SharedTextUrl.extract(rawClipboardText) as? SharedTextUrl.Extraction.Single)?.url
        _uiState.update {
            it.copy(
                lastSeenClipboardText = rawClipboardText,
                clipboardSuggestion = candidate?.takeIf { url -> url != state.url },
            )
        }
    }

    /** The user tapped the clipboard suggestion: fill it in and resolve immediately. */
    fun onClipboardSuggestionAccepted() {
        val url = _uiState.value.clipboardSuggestion ?: return
        onSharedUrlReceived(url)
    }

    /** The user dismissed the clipboard suggestion for this clip. */
    fun onClipboardSuggestionDismissed() {
        _uiState.update { it.copy(clipboardSuggestion = null) }
    }

    /**
     * PHASE 17: entry point for Android Share intake. Any in-flight resolve
     * is cancelled deterministically, the shared URL fills the form, and
     * resolution starts — the user still chooses Download explicitly.
     */
    fun onSharedUrlReceived(url: String) {
        resolveJob?.cancel()
        resolveJob = null
        autoResolveJob?.cancel()
        autoResolveJob = null
        _uiState.update { it.copy(isResolving = false, clipboardSuggestion = null) }
        onUrlChange(url)
        onResolve()
    }

    /** Resets the one-shot "queued" toast/scaffold flag after UI consumed it. */
    fun onQueuedMessageShown() {
        _uiState.update { it.copy(queuedSuccessfully = false) }
    }

    /**
     * Safe error mapping — the user never sees URLs, query strings,
     * credentials, paths, stack traces or raw exception messages.
     */
    private companion object {
        const val AUTO_RESOLVE_DEBOUNCE_MS = 500L
    }

    private fun messageFor(error: Throwable): String = when (error) {
        is MediaResolveException.UnsupportedSource ->
            "This isn't a direct media file link — dedicated platforms aren't supported yet."
        is MediaResolveException.UnsupportedProvider ->
            // PHASE 17: recognized provider, honest "not implemented yet".
            "${error.provider.displayName} links aren't supported yet — " +
                "extraction for this provider hasn't been implemented."
        is MediaResolveException.ExtractionUnavailable ->
            // PHASE 19: provider is recognized but no legitimate, authorized
            // retrieval path exists — the honest message, no "Download failed".
            "Content from ${error.provider.displayName} can't be retrieved yet — " +
                "there's no legitimate access path available to this app."
        is MediaResolveException.ExtractionFailed -> when (error.reason) {
            MediaResolveException.ExtractionFailed.Reason.LOGIN_REQUIRED ->
                "${error.provider.displayName} asked for a login to show this link. " +
                    "Import your browser's cookies.txt in Settings and try again."
            MediaResolveException.ExtractionFailed.Reason.UNAVAILABLE ->
                "This media is private, removed, or blocked in your region."
            MediaResolveException.ExtractionFailed.Reason.NO_MEDIA ->
                "No downloadable video was found at this link."
            MediaResolveException.ExtractionFailed.Reason.RATE_LIMITED ->
                "${error.provider.displayName} is limiting requests right now. Try again in a while."
            MediaResolveException.ExtractionFailed.Reason.NETWORK ->
                "Couldn't reach ${error.provider.displayName}. Check your connection and try again."
            MediaResolveException.ExtractionFailed.Reason.ENGINE_UNAVAILABLE ->
                "The download engine couldn't start. Restart the app and try again."
            MediaResolveException.ExtractionFailed.Reason.OTHER ->
                "Couldn't read this ${error.provider.displayName} link. The site may have changed — " +
                    "restart the app so the engine can update, then try again."
        }
        is MediaResolveException.Http ->
            "The source server answered with an error (HTTP ${error.statusCode})."
        is MediaResolveException.Network ->
            "Couldn't reach the source server. Check your connection and try again."
        is MediaResolveException.Policy ->
            "This link points to a blocked destination and can't be used."
        else -> "Couldn't resolve that link."
    }
}
