package com.narcictub.app.ui.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.narcictub.app.ui.theme.NarcicTubTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

/**
 * PHASE 11 — in-app playback of a completed download. Real player state
 * only: real position, real duration (hidden entirely when unknown), real
 * video dimensions. Errors are safe user-facing messages — never URIs,
 * paths or internal exceptions. The player itself is owned by the
 * ViewModel; this screen only renders state and forwards controls.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlaybackViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // PHASE 22: pause when the audio route becomes noisy (headphones
    // unplugged, Bluetooth disconnect) — standard Android media behavior,
    // replay stays an explicit user action. The receiver is registered NOT
    // exported and unregistered with the composable.
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                    viewModel.onBecameNoisy()
                }
            }
        }
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        ContextCompat.registerReceiver(
            context.applicationContext,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { context.applicationContext.unregisterReceiver(receiver) }
    }

    // Real position polling while playing — 500ms cadence driven by the UI,
    // values always from the player itself (no simulated progress).
    LaunchedEffect(state.isPlaying) {
        if (state.isPlaying) {
            while (kotlin.coroutines.coroutineContext.isActive) {
                delay(500)
                viewModel.onTick()
            }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = state.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Go back")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (state.phase) {
                PlaybackPhase.LOADING -> PlaybackLoading(Modifier.fillMaxSize())
                PlaybackPhase.UNAVAILABLE -> PlaybackMessage(
                    message = state.errorMessage.orEmpty(),
                    onBack = onBack,
                    modifier = Modifier.fillMaxSize(),
                )
                PlaybackPhase.ERROR -> PlaybackMessage(
                    message = state.errorMessage.orEmpty(),
                    onBack = onBack,
                    modifier = Modifier.fillMaxSize(),
                )
                PlaybackPhase.READY -> PlaybackReadyContent(
                    state = state,
                    onTogglePlay = viewModel::onTogglePlay,
                    onSeekTo = viewModel::onSeekTo,
                    onSeekBy = viewModel::onSeekBy,
                    onSurfaceAvailable = viewModel::onSurfaceAvailable,
                    onSurfaceDestroyed = viewModel::onSurfaceDestroyed,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun PlaybackReadyContent(
    state: PlaybackUiState,
    onTogglePlay: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSeekBy: (Long) -> Unit,
    onSurfaceAvailable: (Any?) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        if (state.isVideo) {
            // Video surface; the ViewModel owns the player, this view only
            // supplies (and detaches) the surface.
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(state.aspectRatio ?: (16f / 9f))
                    .background(Color.Black),
                factory = { context ->
                    TextureView(context).apply {
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(
                                surfaceTexture: android.graphics.SurfaceTexture,
                                width: Int,
                                height: Int,
                            ) {
                                onSurfaceAvailable(Surface(surfaceTexture))
                            }

                            override fun onSurfaceTextureSizeChanged(
                                surfaceTexture: android.graphics.SurfaceTexture,
                                width: Int,
                                height: Int,
                            ) {
                            }

                            override fun onSurfaceTextureDestroyed(
                                surfaceTexture: android.graphics.SurfaceTexture,
                            ): Boolean {
                                onSurfaceDestroyed()
                                return true
                            }

                            override fun onSurfaceTextureUpdated(
                                surfaceTexture: android.graphics.SurfaceTexture,
                            ) {
                            }
                        }
                    }
                },
            )
        } else {
            // Audio-oriented presentation: no fake artwork, no fake video.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(56.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        if (state.isBuffering) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
            )
        }

        // Controls: position (real), seek only when duration is really known.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val duration = state.durationMs
            if (duration != null) {
                var dragging by remember { mutableStateOf<Float?>(null) }
                Slider(
                    value = dragging ?: state.positionMs.toFloat().coerceIn(0f, duration.toFloat()),
                    onValueChange = { dragging = it },
                    onValueChangeFinished = {
                        dragging?.let { onSeekTo(it.toLong()) }
                        dragging = null
                    },
                    valueRange = 0f..duration.toFloat(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = formatPlaybackTime(state.positionMs),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = formatPlaybackTime(duration),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            } else {
                // Duration genuinely unknown: show the real elapsed time only.
                Text(
                    text = formatPlaybackTime(state.positionMs),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            FilledIconButton(
                onClick = onTogglePlay,
                modifier = Modifier
                    .padding(top = 12.dp)
                    .size(64.dp),
            ) {
                if (state.isPlaying) {
                    Icon(Icons.Filled.Pause, contentDescription = "Pause")
                } else {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Play")
                }
            }
            // PHASE 22: relative seek controls — clamped in the ViewModel.
            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                IconButton(onClick = { onSeekBy(-10_000) }) {
                    Icon(Icons.Filled.Replay10, contentDescription = "Back 10 seconds")
                }
                Text(
                    text = when {
                        state.isCompleted -> "Completed — tap play to replay"
                        state.isBuffering -> "Buffering…"
                        state.isPlaying -> "Playing"
                        else -> "Paused"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(onClick = { onSeekBy(10_000) }) {
                    Icon(Icons.Filled.Forward10, contentDescription = "Forward 10 seconds")
                }
            }
        }
    }
}

@Composable
private fun PlaybackLoading(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text(
                text = "Preparing playback…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun PlaybackMessage(
    message: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Button(onClick = onBack, modifier = Modifier.padding(top = 16.dp)) {
                Text("Go back")
            }
        }
    }
}

/** Real player position/duration formatted; never a fabricated value. */
internal fun formatPlaybackTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
