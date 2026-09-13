package com.narcictub.app.ui.home

/**
 * UI state for the Home URL form. `resolvedHost` renders only the host of a
 * genuinely resolved [com.narcictub.app.domain.model.MediaInfo] — no fake
 * metadata. `queuedSuccessfully` is a one-shot flag consumed by the UI.
 */
data class HomeUiState(
    val url: String = "",
    val isUrlValid: Boolean = false,
    val validationMessage: String? = null,
    val isResolving: Boolean = false,
    val resolvedHost: String? = null,
    val isDownloading: Boolean = false,
    val queuedSuccessfully: Boolean = false,
    val errorMessage: String? = null,
)
