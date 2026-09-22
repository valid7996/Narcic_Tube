package com.narcictub.app.ui.home

import androidx.lifecycle.ViewModel
import com.narcictub.app.domain.share.SharedTextUrl
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * PHASE 17 — activity-scoped intake for URLs received through Android
 * Share. The activity hands raw shared text here; this ViewModel turns it
 * into a ONE-SHOT, validated [PendingShare] event that the Home screen
 * consumes exactly once.
 *
 * Lifecycle safety: this ViewModel lives with the activity, so it survives
 * configuration changes — a consumed event cannot be re-processed after a
 * rotation. There is no static/global state and no auto-download: the
 * Home screen only pre-fills and resolves; downloading stays a deliberate
 * user action.
 */
sealed interface PendingShare {
    /** A single validated http/https URL to pre-fill and resolve on Home. */
    data class Url(val url: String) : PendingShare

    /** The shared text contained no usable (or ambiguous) link — safe message. */
    data class Invalid(val message: String) : PendingShare
}

@HiltViewModel
class ShareIntakeViewModel @Inject constructor() : ViewModel() {

    private val _pending = MutableStateFlow<PendingShare?>(null)
    val pending: StateFlow<PendingShare?> = _pending.asStateFlow()

    /** Called by the activity for every new share intent (text/plain). */
    fun onNewSharedText(rawText: String?) {
        _pending.value = when (val extraction = SharedTextUrl.extract(rawText)) {
            is SharedTextUrl.Extraction.Single -> PendingShare.Url(extraction.url)
            is SharedTextUrl.Extraction.Ambiguous -> PendingShare.Invalid(
                "The shared text contains ${extraction.candidates} different links. " +
                    "Copy the one you want and paste it directly.",
            )
            SharedTextUrl.Extraction.None ->
                PendingShare.Invalid("The shared text doesn't contain a supported link.")
        }
    }

    /** Called by the Home screen after it consumed the event. */
    fun onConsumed() {
        _pending.value = null
    }
}
