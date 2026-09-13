package com.narcictub.app.ui.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.narcictub.app.domain.model.DownloadsOverview
import com.narcictub.app.domain.model.HistoryItem
import com.narcictub.app.domain.usecase.CancelDownloadUseCase
import com.narcictub.app.domain.usecase.ObserveDownloadsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    observeDownloads: ObserveDownloadsUseCase,
    private val cancelDownload: CancelDownloadUseCase,
) : ViewModel() {

    val overview: StateFlow<DownloadsOverview> = observeDownloads()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DownloadsOverview())

    private val _cancelError = MutableStateFlow<String?>(null)
    val cancelError: StateFlow<String?> = _cancelError.asStateFlow()

    fun onCancel(id: Long) {
        viewModelScope.launch {
            runCatching { cancelDownload(id) }
                .onFailure { _cancelError.update { "Couldn't cancel that download." } }
        }
    }

    fun onDismissError() {
        _cancelError.update { null }
    }
}
