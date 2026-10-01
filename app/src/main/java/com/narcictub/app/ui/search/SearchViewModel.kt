package com.narcictub.app.ui.search

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.narcictub.app.data.network.YouTubeClient
import com.narcictub.app.domain.model.YoutubeSearchItem
import com.narcictub.app.domain.usecase.EnqueueDownloadUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * HONEY — YouTube search screen logic (REAL): real InnerTube search results,
 * real stream resolution for in-app playback, and downloads that queue into
 * the hive through the standard pipeline. Nothing simulated.
 */
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val youTubeClient: YouTubeClient,
    private val enqueueDownload: EnqueueDownloadUseCase,
) : ViewModel() {

    data class Playback(val title: String, val streamUrl: String)

    var query by androidx.compose.runtime.mutableStateOf("")
        private set

    private val _results = MutableStateFlow<List<YoutubeSearchItem>>(emptyList())
    val results: StateFlow<List<YoutubeSearchItem>> = _results.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** در حال گرفتن آدرس استریم برای پخش. */
    private val _preparing = MutableStateFlow<YoutubeSearchItem?>(null)
    val preparing: StateFlow<YoutubeSearchItem?> = _preparing.asStateFlow()

    /** استریم آماده پخش. */
    private val _playback = MutableStateFlow<Playback?>(null)
    val playback: StateFlow<Playback?> = _playback.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun onQueryChange(value: String) {
        query = value
    }

    fun search() {
        val q = query.trim()
        if (q.isBlank() || _isSearching.value) return
        viewModelScope.launch {
            _isSearching.value = true
            _error.value = null
            try {
                _results.value = youTubeClient.search(q)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _results.value = emptyList()
                _error.value = "Search failed — check your connection (VPN/DoH) and try again."
            } finally {
                _isSearching.value = false
            }
        }
    }

    /** پخش: آدرس استریم مستقیم از InnerTube ANDROID client گرفته می‌شود. */
    fun play(context: Context, item: YoutubeSearchItem) {
        if (_preparing.value != null) return
        _preparing.value = item
        viewModelScope.launch {
            val stream = try {
                youTubeClient.resolveStream(item.watchUrl)
            } catch (_: Exception) {
                null
            }
            _preparing.value = null
            if (stream != null) {
                _playback.value = Playback(stream.title ?: item.title, stream.url)
            } else {
                _message.value = "Direct playback isn't available for this video — try Download."
            }
        }
    }

    /** دانلود: از طریق همان خط واقعی دانلود (yt-dlp با انتخاب فرمت) داخل کندو. */
    fun download(item: YoutubeSearchItem) {
        viewModelScope.launch {
            val result = enqueueDownload(item.watchUrl, item.durationSeconds, item.title)
            _message.value = result.fold(
                onSuccess = { "Added to the hive · ${item.title}" },
                onFailure = { "Couldn't queue that download." },
            )
            kotlinx.coroutines.delay(2600)
            _message.value = null
        }
    }

    fun onMessageShown() {
        _message.value = null
    }

    fun onPlaybackClosed() {
        _playback.value = null
    }
}
