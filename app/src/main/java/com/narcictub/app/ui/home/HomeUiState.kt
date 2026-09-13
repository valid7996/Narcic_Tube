package com.narcictub.app.ui.home

/**
 * UI state for the Home URL form. Kept in the ui layer — the domain layer
 * only owns pure validation ([com.narcictub.app.domain.UrlValidator]).
 */
data class HomeUiState(
    val url: String = "",
    val isUrlValid: Boolean = false,
    val validationMessage: String? = null,
    val isResolving: Boolean = false,
    val resolvedHost: String? = null,
    val errorMessage: String? = null,
)
