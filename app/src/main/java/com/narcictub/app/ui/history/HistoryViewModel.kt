package com.narcictub.app.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.narcictub.app.domain.model.MediaFileAvailability
import com.narcictub.app.domain.usecase.CheckMediaAvailabilityUseCase
import com.narcictub.app.domain.usecase.OpenCompletedMediaUseCase
import com.narcictub.app.domain.usecase.ObserveDownloadsUseCase
import com.narcictub.app.domain.usecase.RemoveCompletedDownloadsUseCase
import com.narcictub.app.domain.usecase.RemoveDownloadUseCase
import com.narcictub.app.domain.usecase.RemoveFailedDownloadsUseCase
import com.narcictub.app.domain.usecase.RemoveHistoryRecordUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * PHASE 10 — History screen logic. State comes from the existing
 * [ObserveDownloadsUseCase] (no second pipeline); completed rows' file
 * availability is checked through the repository backend once per row per
 * session, and every action (open / remove record / delete file+record /
 * clear failed) runs through domain use cases with duplicate-tap
 * suppression. All user-facing messages are safe — no URLs, paths, stack
 * traces or raw exceptions.
 *
 * Semantics preserved: "remove from history" NEVER deletes the published
 * file; deleting media is a separate, explicitly confirmed action.
 */
@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModel @Inject constructor(
    observeDownloads: ObserveDownloadsUseCase,
    private val checkAvailability: CheckMediaAvailabilityUseCase,
    private val openMedia: OpenCompletedMediaUseCase,
    private val removeRecordAction: RemoveHistoryRecordUseCase,
    private val removeFileAndRecord: RemoveDownloadUseCase,
    private val clearFailed: RemoveFailedDownloadsUseCase,
    private val clearCompleted: RemoveCompletedDownloadsUseCase,
) : ViewModel() {

    /**
     * PHASE 12: load errors never crash. A persistence failure is mapped to
     * a safe error snapshot ([com.narcictub.app.domain.model.DownloadsOverview.isError])
     * with no raw exception details, and [onRetryLoad] re-opens the stream.
     */
    private val retryKey = MutableStateFlow(0)

    val overview: StateFlow<com.narcictub.app.domain.model.DownloadsOverview> = retryKey
        .flatMapLatest {
            observeDownloads().catch {
                emit(
                    com.narcictub.app.domain.model.DownloadsOverview(
                        items = emptyList(),
                        isLoading = false,
                        isError = true,
                    ),
                )
            }
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            com.narcictub.app.domain.model.DownloadsOverview(),
        )

    /** Re-opens the persistence stream after a load error. */
    fun onRetryLoad() {
        retryKey.update { it + 1 }
    }

    /** Availability per completed row, checked lazily (one check per row). */
    private val _availability =
        MutableStateFlow<Map<Long, MediaFileAvailability>>(emptyMap())
    val availability: StateFlow<Map<Long, MediaFileAvailability>> = _availability.asStateFlow()

    private val _transient = MutableStateFlow(HistoryTransientUiState())
    val transient: StateFlow<HistoryTransientUiState> = _transient.asStateFlow()

    /** Ids with an action already in flight — duplicate-tap guard. */
    private val pendingActions = mutableSetOf<Long>()

    init {
        viewModelScope.launch {
            overview.collect { snapshot ->
                val toCheck = snapshot.items
                    .filter {
                        it.status == com.narcictub.app.domain.model.DownloadStatus.COMPLETED &&
                            !it.localUri.isNullOrBlank() &&
                            it.id !in _availability.value
                    }
                for (item in toCheck) {
                    // Availability of a row never changes while the row lives;
                    // one honest backend check per completed row per session.
                    // A failed check leaves the row unchecked (unknown) —
                    // never a fabricated verdict.
                    val result = runCatching { checkAvailability(item.id) }.getOrNull()
                        ?: continue
                    _availability.update { it + (item.id to result) }
                }
            }
        }
    }

    /** Open a completed file: validated + availability-checked via use case. */
    fun onOpen(id: Long) {
        if (!markPending(id)) return
        viewModelScope.launch {
            try {
                val media = openMedia(id)
                if (media != null) {
                    _availability.update {
                        it + (id to MediaFileAvailability.AVAILABLE)
                    }
                    _transient.update { it.copy(pendingOpen = media) }
                } else {
                    _availability.update {
                        it + (id to MediaFileAvailability.UNAVAILABLE)
                    }
                    _transient.update { it.copy(infoMessage = FILE_MISSING) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _transient.update { it.copy(errorMessage = OPEN_FAILED) }
            } finally {
                clearPending(id)
            }
        }
    }

    /** Called by the screen after it launched the ACTION_VIEW intent. */
    fun onOpenRequestShown() {
        _transient.update { it.copy(pendingOpen = null) }
    }

    /** Called by the screen when the intent could not be handled. */
    fun onOpenLaunchFailed() {
        _transient.update { it.copy(errorMessage = NO_APP) }
    }

    /**
     * PHASE 11: in-app playback. Validates + availability-checks the record
     * through the same Phase 10 use case, then hands ONLY the record id to
     * navigation — the playback screen re-validates before preparing, never
     * trusting navigation or UI state.
     */
    fun onPlay(id: Long) {
        if (_transient.value.pendingPlayback != null) return // one launch at a time
        if (!markPending(id)) return
        viewModelScope.launch {
            try {
                val media = openMedia(id)
                if (media != null) {
                    _availability.update {
                        it + (id to MediaFileAvailability.AVAILABLE)
                    }
                    _transient.update { it.copy(pendingPlayback = id) }
                } else {
                    _availability.update {
                        it + (id to MediaFileAvailability.UNAVAILABLE)
                    }
                    _transient.update { it.copy(infoMessage = FILE_MISSING) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _transient.update { it.copy(errorMessage = OPEN_FAILED) }
            } finally {
                clearPending(id)
            }
        }
    }

    /** Called by the screen after it navigated to the playback screen. */
    fun onPlaybackLaunched() {
        _transient.update { it.copy(pendingPlayback = null) }
    }

    /** Removes ONLY the history record — the file, if any, stays. */
    fun onRemoveRecord(id: Long) {
        if (!markPending(id)) return
        viewModelScope.launch {
            try {
                removeRecordAction(id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _transient.update { it.copy(errorMessage = REMOVE_RECORD_FAILED) }
            } finally {
                clearPending(id)
            }
        }
    }

    /**
     * Deletes the published file AND the record — destructive; the screen
     * only calls this after the explicit confirmation dialog.
     */
    fun onDeleteFileAndRecord(id: Long) {
        if (!markPending(id)) return
        viewModelScope.launch {
            try {
                removeFileAndRecord(id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _transient.update { it.copy(errorMessage = DELETE_FAILED) }
            } finally {
                clearPending(id)
            }
        }
    }

    /** Bulk clear of failed/cancelled records (no files exist for them). */
    fun onClearFailedConfirmed() {
        viewModelScope.launch {
            try {
                val n = clearFailed()
                if (n > 0) _transient.update { it.copy(infoMessage = removedMessage(n)) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _transient.update { it.copy(errorMessage = REMOVE_RECORD_FAILED) }
            }
        }
    }

    /**
     * PHASE 12: bulk clear of COMPLETED records through the existing
     * [RemoveCompletedDownloadsUseCase] — this deletes the published files
     * too, so the screen only calls it after the explicit confirmation.
     */
    fun onClearCompletedConfirmed() {
        viewModelScope.launch {
            try {
                val n = clearCompleted()
                if (n > 0) _transient.update { it.copy(infoMessage = removedWithFilesMessage(n)) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _transient.update { it.copy(errorMessage = DELETE_FAILED) }
            }
        }
    }

    /** Resets one-shot feedback after the UI showed it. */
    fun onMessageShown() {
        _transient.update { it.copy(errorMessage = null, infoMessage = null) }
    }

    private fun markPending(id: Long): Boolean = synchronized(pendingActions) {
        pendingActions.add(id)
    }

    private fun clearPending(id: Long) {
        synchronized(pendingActions) { pendingActions.remove(id) }
    }

    private fun removedMessage(n: Int): String =
        "Removed $n ${if (n == 1) "record" else "records"}."

    private fun removedWithFilesMessage(n: Int): String =
        "Removed $n ${if (n == 1) "download" else "downloads"} and their files."

    companion object {
        private const val FILE_MISSING =
            "The downloaded file is no longer available on this device."
        private const val OPEN_FAILED = "Couldn't open that file."
        private const val NO_APP = "No installed app can open this file."
        private const val REMOVE_RECORD_FAILED = "Couldn't remove that record."
        private const val DELETE_FAILED = "Couldn't delete that download."
    }
}

/** One-shot transient UI feedback (messages + pending open/play requests). */
data class HistoryTransientUiState(
    val errorMessage: String? = null,
    val infoMessage: String? = null,
    val pendingOpen: com.narcictub.app.domain.model.OpenableMedia? = null,
    /** PHASE 11: validated record id awaiting navigation to the player. */
    val pendingPlayback: Long? = null,
)
