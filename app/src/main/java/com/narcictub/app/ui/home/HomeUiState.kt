package com.narcictub.app.ui.home

import com.narcictub.app.domain.model.MediaInfo
import com.narcictub.app.domain.model.MediaVariant

/**
 * UI state for the Home URL form (Phase 20: variant selection added).
 *
 * STALE-SELECTION PROTECTION: [selectedVariantUrl] is an IDENTITY
 * (the variant's policy-validated download URL) that must belong to the
 * CURRENT [resolvedMedia]. It is cleared whenever the resolved media is
 * replaced, a new URL is typed, a resolve fails, or the form is cleared;
 * [selectedVariant] is DERIVED by membership lookup, so a selection that no
 * longer belongs to the current media can never be downloaded — the
 * Download gate simply closes.
 */
data class HomeUiState(
    val url: String = "",
    val isUrlValid: Boolean = false,
    val validationMessage: String? = null,
    val isResolving: Boolean = false,
    val resolvedMedia: MediaInfo? = null,
    /** Identity (downloadUrl) of the selected variant; null = nothing selected. */
    val selectedVariantUrl: String? = null,
    val isDownloading: Boolean = false,
    val queuedSuccessfully: Boolean = false,
    val errorMessage: String? = null,
    /** A supported link currently sitting on the clipboard, offered as a one-tap suggestion. */
    val clipboardSuggestion: String? = null,
    /** The last clipboard text already looked at, so the same copy is never re-suggested. */
    val lastSeenClipboardText: String? = null,
) {
    /**
     * The selected variant, resolved by identity against the CURRENT
     * media's real variants. Null when nothing is selected or the
     * selection is stale — stale selections are never downloadable.
     */
    val selectedVariant: MediaVariant?
        get() = resolvedMedia?.variants?.firstOrNull { it.downloadUrl == selectedVariantUrl }

    /**
     * Variants in the documented deterministic display order (see
     * [sortedVariantsForDisplay]) — every real variant is shown, none is
     * ever discarded or invented.
     */
    val displayVariants: List<MediaVariant>
        get() = resolvedMedia?.variants?.let { sortedVariantsForDisplay(it) } ?: emptyList()

    /**
     * Download is possible ONLY for the current, genuinely resolved media
     * with a selected variant that belongs to it. Resolution alone, failed
     * resolution, no-variant media, and stale selections all close this
     * gate. The final security validation happens again inside the enqueue
     * use case and the policy-gated downloader.
     */
    val canDownload: Boolean
        get() = isUrlValid && !isResolving && selectedVariant != null
}

/**
 * Deterministic variant display ordering (Phase 20, documented rule):
 *  1. known video height DESCENDING (best-known video quality first),
 *  2. known size DESCENDING (larger — usually higher quality — first),
 *  3. downloadUrl ASCENDING as a stable final tie-break.
 * Null/unknown metadata sorts after known values at its own level, and NO
 * variant is ever dropped: the output is a permutation of the input.
 */
internal fun sortedVariantsForDisplay(variants: List<MediaVariant>): List<MediaVariant> =
    variants.sortedWith(
        compareByDescending<MediaVariant> { it.height }
            .thenByDescending { it.sizeBytes }
            .thenBy { it.downloadUrl },
    )
