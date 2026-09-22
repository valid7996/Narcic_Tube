package com.narcictub.app.ui.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.narcictub.app.domain.model.DownloadsOverview
import com.narcictub.app.domain.usecase.CancelDownloadUseCase
import com.narcictub.app.domain.usecase.ObserveDownloadsUseCase
import com.narcictub.app.domain.usecase.RemoveCompletedDownloadsUseCase
import com.narcictub.app.domain.usecase.RemoveDownloadUseCase
import com.narcictub.app.domain.usecase.RemoveFailedDownloadsUseCase
import com.narcictub.app.domain.usecase.RetryDownloadUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Downloads screen logic (Phase 7). State comes from
 * [ObserveDownloadsUseCase] (real history + real progress — nothing
 * simulated). All actions go through use cases; the ViewModel adds only
 * per-id duplicate-tap suppression and safe, user-readable feedback.
 * It creates no scopes of its own — everything runs in viewModelScope.
 */
@HiltViewModel
class DownloadsViewModel @Inject constructor(
    observeDownloads: ObserveDownloadsUseCase,
    private val cancelDownload: CancelDownloadUseCase,
    private val retryDownload: RetryDownloadUseCase,
    private val removeDownload: RemoveDownloadUseCase,
    private val removeCompleted: RemoveCompletedDownloadsUseCase,
    private val removeFailed: RemoveFailedDownloadsUseCase,
) : ViewModel() {

    val overview: StateFlow<DownloadsOverview> = observeDownloads()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DownloadsOverview())

    private val _transient = MutableStateFlow(DownloadsTransientUiState())
    val transient: StateFlow<DownloadsTransientUiState> = _transient.asStateFlow()

    /** Ids with an action already in flight — duplicate-tap guard. */
    private val pendingActions = mutableSetOf<Long>()

    fun onCancel(id: Long) {
        if (!markPending(id)) return
        viewModelScope.launch {
            try {
                cancelDownload(id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _transient.update { it.copy(errorMessage = CANCEL_FAILED) }
            } finally {
                clearPending(id)
            }
        }
    }

    fun onRetry(id: Long) {
        if (!markPending(id)) return
        viewModelScope.launch {
            try {
                val newId = retryDownload(id)
                if (newId == null) {
                    _transient.update { it.copy(errorMessage = RETRY_UNAVAILABLE) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _transient.update { it.copy(errorMessage = RETRY_FAILED) }
            } finally {
                clearPending(id)
            }
        }
    }

    fun onRemove(id: Long) {
        if (!markPending(id)) return
        viewModelScope.launch {
            try {
                removeDownload(id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _transient.update { it.copy(errorMessage = REMOVE_FAILED) }
            } finally {
                clearPending(id)
            }
        }
    }

    /**
     * Bulk: clear every finished record (COMPLETED). Called only after the
     * screen showed a confirmation dialog — the ViewModel does not confirm.
     */
    fun onRemoveCompletedConfirmed() {
        viewModelScope.launch {
            try {
                val n = removeCompleted()
                if (n > 0) _transient.update { it.copy(removedCountMessage = removedMessage(n)) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _transient.update { it.copy(errorMessage = REMOVE_FAILED) }
            }
        }
    }

    /**
     * Bulk: clear every failed/cancelled record. Called only after the
     * screen showed a confirmation dialog — the ViewModel does not confirm.
     */
    fun onRemoveFailedConfirmed() {
        viewModelScope.launch {
            try {
                val n = removeFailed()
                if (n > 0) _transient.update { it.copy(removedCountMessage = removedMessage(n)) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _transient.update { it.copy(errorMessage = REMOVE_FAILED) }
            }
        }
    }

    /** Resets one-shot feedback after the UI showed it. */
    fun onMessageShown() {
        _transient.update { DownloadsTransientUiState() }
    }

    private fun markPending(id: Long): Boolean = synchronized(pendingActions) {
        pendingActions.add(id)
    }

    private fun clearPending(id: Long) {
        synchronized(pendingActions) { pendingActions.remove(id) }
    }

    private fun removedMessage(n: Int): String =
        "Removed $n ${if (n == 1) "download" else "downloads"}."

    companion object {
        private const val CANCEL_FAILED = "Couldn't cancel that download."
        private const val RETRY_UNAVAILABLE = "That download can't be retried right now."
        private const val RETRY_FAILED = "Couldn't re-queue that download."
        private const val REMOVE_FAILED = "Couldn't remove that download."
    }
}

/** One-shot transient UI feedback (errors / bulk-action result). */
data class DownloadsTransientUiState(
    val errorMessage: String? = null,
    val removedCountMessage: String? = null,
)
