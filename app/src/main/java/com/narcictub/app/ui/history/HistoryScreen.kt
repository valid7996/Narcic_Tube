package com.narcictub.app.ui.history

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.narcictub.app.domain.model.DownloadStatus
import com.narcictub.app.domain.model.DownloadsOverview
import com.narcictub.app.domain.model.MediaFileAvailability
import com.narcictub.app.ui.theme.NarcicTubTheme
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * PHASE 10 — History screen: records management over the existing Room
 * history, without a second download pipeline. Sections distinguish
 * active/queued, completed, and failed/cancelled records; completed rows
 * offer a safe "Open" that launches a standard ACTION_VIEW intent for a
 * URI the repository already validated (content:// or app-contained file://
 * only), and honestly show when the file is no longer available.
 *
 * SEMANTICS: "Remove from history" never deletes the user's media — the
 * destructive "delete file and record" is a separate, explicitly confirmed
 * choice in the removal dialog. All rows show only safe metadata: title,
 * host, format, size, date, status — never URLs, query strings, paths or
 * raw exceptions.
 */
@Composable
fun HistoryScreen(
    modifier: Modifier = Modifier,
    onPlayMedia: (Long) -> Unit = {},
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val overview by viewModel.overview.collectAsStateWithLifecycle()
    val availability by viewModel.availability.collectAsStateWithLifecycle()
    val transient by viewModel.transient.collectAsStateWithLifecycle()

    HistoryScreenContent(
        overview = overview,
        availability = availability,
        transient = transient,
        onOpen = viewModel::onOpen,
        onOpenRequestShown = viewModel::onOpenRequestShown,
        onOpenLaunchFailed = viewModel::onOpenLaunchFailed,
        onPlay = viewModel::onPlay,
        onPlaybackLaunched = viewModel::onPlaybackLaunched,
        onPlayMedia = onPlayMedia,
        onRemoveRecord = viewModel::onRemoveRecord,
        onDeleteFileAndRecord = viewModel::onDeleteFileAndRecord,
        onClearFailed = viewModel::onClearFailedConfirmed,
        onClearCompleted = viewModel::onClearCompletedConfirmed,
        onRetryLoad = viewModel::onRetryLoad,
        onMessageShown = viewModel::onMessageShown,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreenContent(
    overview: DownloadsOverview,
    availability: Map<Long, MediaFileAvailability>,
    transient: HistoryTransientUiState,
    onOpen: (Long) -> Unit,
    onOpenRequestShown: () -> Unit,
    onOpenLaunchFailed: () -> Unit,
    onPlay: (Long) -> Unit,
    onPlaybackLaunched: () -> Unit,
    onPlayMedia: (Long) -> Unit,
    onRemoveRecord: (Long) -> Unit,
    onDeleteFileAndRecord: (Long) -> Unit,
    onClearFailed: () -> Unit,
    onClearCompleted: () -> Unit,
    onRetryLoad: () -> Unit,
    onMessageShown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = remember(overview, availability) {
        overview.toHistoryUiState(availability)
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    // One-shot open request: the ViewModel validated the record; the screen
    // only launches the standard intent. Failures surface as safe messages.
    LaunchedEffect(transient.pendingOpen) {
        val media = transient.pendingOpen ?: return@LaunchedEffect
        try {
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(android.net.Uri.parse(media.uriText), media.mimeType)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            onOpenLaunchFailed()
        } catch (_: SecurityException) {
            onOpenLaunchFailed()
        } catch (_: android.os.FileUriExposedException) {
            // API 26–28 legacy records hold APP-OWNED file:// URIs (the
            // approved pre-Q publisher form). The platform forbids exposing
            // file:// to other apps and throws this RuntimeException —
            // reported as the safe open-launch failure, never a crash.
            onOpenLaunchFailed()
        }
        onOpenRequestShown()
    }

    val message = transient.errorMessage ?: transient.infoMessage
    LaunchedEffect(message) {
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            onMessageShown()
        }
    }

    // PHASE 11: one-shot playback launch — the ViewModel validated the
    // record; navigation carries only the record id (the playback screen
    // re-validates before preparing).
    LaunchedEffect(transient.pendingPlayback) {
        val itemId = transient.pendingPlayback ?: return@LaunchedEffect
        onPlaybackLaunched()
        onPlayMedia(itemId)
    }

    var confirmRemoveId by remember { mutableStateOf<Long?>(null) }
    var confirmClearFailed by remember { mutableStateOf(false) }
    var confirmClearCompleted by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("History") },
                actions = {
                    // PHASE 12: bulk clear of completed records — deletes the
                    // published files too, so it lives behind a confirmation.
                    if (state.hasClearableCompleted) {
                        IconButton(onClick = { confirmClearCompleted = true }) {
                            Icon(
                                Icons.Filled.DeleteSweep,
                                contentDescription = "Clear completed downloads",
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.showLoading -> {
                HistoryLoadingState(Modifier.padding(padding).fillMaxSize())
            }
            state.showError -> {
                // PHASE 12: persistence failure — honest error state with a
                // real retry path; no crash, no fabricated list.
                HistoryErrorState(
                    onRetry = onRetryLoad,
                    modifier = Modifier.padding(padding).fillMaxSize(),
                )
            }
            state.isEmpty -> {
                HistoryEmptyState(Modifier.padding(padding).fillMaxSize())
            }
            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // PHASE 12: if a load error occurred after data was shown,
                    // surface a compact banner instead of hiding real records.
                    if (state.hasError) {
                        item(key = "history-load-error") {
                            HistoryErrorBanner(onRetry = onRetryLoad)
                        }
                    }
                    if (state.active.isNotEmpty()) {
                    historySectionHeader("Active")
                    item(key = "history-active-hint") {
                        Text(
                            text = "Manage or cancel these on the Downloads tab.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(state.active, key = { "history-active-${it.id}" }) { row ->
                        HistoryRowCard(row = row, leadingAction = null)
                    }
                }
                if (state.completed.isNotEmpty()) {
                    historySectionHeader("Completed")
                    items(state.completed, key = { "history-completed-${it.id}" }) { row ->
                        HistoryRowCard(
                            row = row,
                            leadingAction = {
                                if (row.availability != MediaFileAvailability.UNAVAILABLE) {
                                    // PHASE 11: in-app playback is the primary action.
                                    IconButton(onClick = { onPlay(row.id) }) {
                                        Icon(
                                            Icons.Filled.PlayArrow,
                                            contentDescription = "Play preview in app",
                                        )
                                    }
                                    // External open stays available (Phase 10).
                                    IconButton(onClick = { onOpen(row.id) }) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.OpenInNew,
                                            contentDescription = "Open in another app",
                                        )
                                    }
                                }
                            },
                            onRemoveRequest = { confirmRemoveId = row.id },
                        )
                    }
                }
                if (state.dismissed.isNotEmpty()) {
                    historySectionHeader(
                        "Failed & cancelled",
                        trailing = {
                            TextButton(
                                onClick = { confirmClearFailed = true },
                                contentPadding = PaddingValues(horizontal = 8.dp),
                            ) { Text("Clear failed") }
                        },
                    )
                    items(state.dismissed, key = { "history-dismissed-${it.id}" }) { row ->
                        // Failed/cancelled records hold no file: removing the
                        // record deletes nothing but the row itself.
                        HistoryRowCard(
                            row = row,
                            leadingAction = null,
                            onRemoveRequest = { onRemoveRecord(row.id) },
                        )
                    }
                }
                }
            }
        }
    }

    // COMPLETED removal: explicit, two-choice confirmation. Keeping the file
    // must always be the non-destructive default.
    if (confirmRemoveId != null) {
        val targetId = confirmRemoveId
        AlertDialog(
            onDismissRequest = { confirmRemoveId = null },
            title = { Text("Remove this download?") },
            text = {
                Text(
                    "You can remove just the history record and keep the " +
                        "downloaded file on this device, or delete the record " +
                        "together with its file permanently.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRemoveId = null
                        if (targetId != null) onRemoveRecord(targetId)
                    },
                ) { Text("Keep file, remove record") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        val id = confirmRemoveId
                        confirmRemoveId = null
                        if (id != null) onDeleteFileAndRecord(id)
                    },
                ) { Text("Delete file too", color = MaterialTheme.colorScheme.error) }
            },
            // Third choice — plain dismiss — offered by the system back/tap.
        )
    }

    if (confirmClearFailed) {
        AlertDialog(
            onDismissRequest = { confirmClearFailed = false },
            title = { Text("Clear failed history?") },
            text = {
                Text(
                    "Removes all failed and cancelled records from history. " +
                        "No downloaded files are affected. This can't be undone.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClearFailed = false
                        onClearFailed()
                    },
                ) { Text("Clear", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearFailed = false }) { Text("Cancel") }
            },
        )
    }

    // PHASE 12: bulk clear of completed records — this one DELETES the
    // published files, so the wording must say exactly that.
    if (confirmClearCompleted) {
        AlertDialog(
            onDismissRequest = { confirmClearCompleted = false },
            title = { Text("Clear completed downloads?") },
            text = {
                Text(
                    "Removes all completed records from history and permanently " +
                        "deletes their downloaded files. This can't be undone.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClearCompleted = false
                        onClearCompleted()
                    },
                ) { Text("Delete files", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearCompleted = false }) { Text("Cancel") }
            },
        )
    }
}

private fun LazyListScope.historySectionHeader(
    title: String,
    trailing: (@Composable () -> Unit)? = null,
) {
    item(key = "history-header-$title") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            trailing?.invoke()
        }
    }
}

@Composable
private fun HistoryRowCard(
    row: UiHistoryRow,
    leadingAction: (@Composable () -> Unit)?,
    onRemoveRequest: (() -> Unit)? = null,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HistoryStatusIcon(status = row.status)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (row.host != null) {
                    Text(
                        text = row.host,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                HistoryDetailLine(row)
            }
            leadingAction?.invoke()
            if (onRemoveRequest != null) {
                IconButton(onClick = onRemoveRequest) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Remove from history",
                    )
                }
            }
        }
    }
}

/** Real metadata only: status, format/MIME when known, size, date, errors. */
@Composable
private fun HistoryDetailLine(row: UiHistoryRow) {
    val parts = buildList {
        add(
            when (row.status) {
                DownloadStatus.QUEUED -> "Queued"
                DownloadStatus.DOWNLOADING -> "Downloading"
                DownloadStatus.PAUSED -> "Paused"
                DownloadStatus.COMPLETED -> "Completed"
                DownloadStatus.FAILED -> row.errorMessage ?: "Failed"
                DownloadStatus.CANCELLED -> "Cancelled"
            },
        )
        if (row.mimeType != null) add(row.mimeType)
        // PHASE 22: real persisted container duration — rendered only when
        // the record actually carries one, never a guess.
        row.durationSeconds?.let { add("Duration: " + formatHistoryDuration(it)) }
        // PHASE 12: real persisted quality label — rendered only when the
        // record actually carries one, never a guess.
        row.quality?.let { add("Quality: $it") }
        if (row.status == DownloadStatus.COMPLETED && row.sizeBytes > 0) {
            add(historyHumanBytes(row.sizeBytes))
        }
        if (row.status == DownloadStatus.COMPLETED && row.availability == MediaFileAvailability.UNAVAILABLE) {
            add("File unavailable")
        }
        if (row.status == DownloadStatus.COMPLETED) {
            row.completedAtEpochMs?.let { add("Completed " + formatCompletedDate(it)) }
        }
    }
    Text(
        text = parts.joinToString("  ·  "),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun HistoryStatusIcon(status: DownloadStatus) {
    when (status) {
        DownloadStatus.QUEUED -> Icon(
            Icons.Filled.HourglassTop,
            contentDescription = "Queued",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DownloadStatus.DOWNLOADING -> Icon(
            Icons.Filled.Downloading,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        DownloadStatus.PAUSED -> Icon(
            Icons.Filled.Close,
            contentDescription = "Paused",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DownloadStatus.COMPLETED -> Icon(
            Icons.Filled.DownloadDone,
            contentDescription = "Completed",
            tint = MaterialTheme.colorScheme.primary,
        )
        DownloadStatus.FAILED -> Icon(
            Icons.Filled.ErrorOutline,
            contentDescription = "Failed",
            tint = MaterialTheme.colorScheme.error,
        )
        DownloadStatus.CANCELLED -> Icon(
            Icons.Filled.Close,
            contentDescription = "Cancelled",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HistoryEmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.VideoLibrary,
            contentDescription = null, // decorative — the text carries meaning
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Text(
            text = "No history yet",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            text = "Completed, failed and queued downloads appear here",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            text = "Queue a link from the Home tab to get started",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

/**
 * PHASE 12: full-screen persistence-failure state — honest, safe wording,
 * and a real retry that re-opens the Room stream. No exception details.
 */
@Composable
private fun HistoryErrorState(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.ErrorOutline,
            contentDescription = null, // decorative — the text carries meaning
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(48.dp),
        )
        Text(
            text = "History couldn't be loaded",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            text = "Something went wrong while reading your downloads. Try again.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
        Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) {
            Text("Try again")
        }
    }
}

/** Compact load-error banner shown above a still-visible list. */
@Composable
private fun HistoryErrorBanner(onRetry: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
            Text(
                text = "History couldn't be refreshed.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            )
            TextButton(onClick = onRetry) { Text("Try again") }
        }
    }
}

@Composable
private fun HistoryLoadingState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text(
            text = "Loading history…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

private fun historyHumanBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / 1e9)
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / 1e6)
    bytes >= 1L shl 10 -> "%.0f KB".format(bytes / 1e3)
    else -> "$bytes B"
}

/** Real persisted duration formatted (m:ss / h:mm:ss); never fabricated. */
internal fun formatHistoryDuration(seconds: Long): String {
    val total = seconds.coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** Local date of the persisted completion instant (real data only). */
internal fun formatCompletedDate(epochMs: Long): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        .withZone(java.time.ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(epochMs))

@Preview(showBackground = true)
@Composable
private fun HistoryEmptyPreview() {
    NarcicTubTheme {
        HistoryScreenContent(
            overview = DownloadsOverview(isLoading = false),
            availability = emptyMap(),
            transient = HistoryTransientUiState(),
            onOpen = {}, onOpenRequestShown = {}, onOpenLaunchFailed = {},
            onPlay = {}, onPlaybackLaunched = {}, onPlayMedia = {},
            onRemoveRecord = {}, onDeleteFileAndRecord = {},
            onClearFailed = {}, onClearCompleted = {}, onRetryLoad = {},
            onMessageShown = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun HistoryLoadedPreview() {
    NarcicTubTheme {
        HistoryScreenContent(
            overview = DownloadsOverview(
                isLoading = false,
                items = listOf(
                    com.narcictub.app.domain.model.HistoryItem(
                        id = 1,
                        sourceUrl = "https://cdn.example.com/video.mp4",
                        title = "video.mp4",
                        mimeType = "video/mp4",
                        status = DownloadStatus.COMPLETED,
                        sizeBytes = 2_500_000,
                        localUri = "content://media/external/downloads/1",
                        createdAt = Instant.now(),
                        completedAt = Instant.now(),
                    ),
                    com.narcictub.app.domain.model.HistoryItem(
                        id = 2,
                        sourceUrl = "https://cdn.example.com/gone.mp4",
                        title = "gone.mp4",
                        status = DownloadStatus.COMPLETED,
                        sizeBytes = 1_000_000,
                        localUri = "content://media/external/downloads/2",
                        createdAt = Instant.now(),
                        completedAt = Instant.now(),
                    ),
                    com.narcictub.app.domain.model.HistoryItem(
                        id = 3,
                        sourceUrl = "https://cdn.example.com/x.mp4",
                        title = "x.mp4",
                        status = DownloadStatus.FAILED,
                        errorMessage = "HTTP error 404",
                        createdAt = Instant.now(),
                    ),
                    com.narcictub.app.domain.model.HistoryItem(
                        id = 4,
                        sourceUrl = "https://cdn.example.com/q.mp4",
                        title = "q.mp4",
                        status = DownloadStatus.QUEUED,
                        createdAt = Instant.now(),
                    ),
                ),
            ),
            availability = mapOf(
                1L to MediaFileAvailability.AVAILABLE,
                2L to MediaFileAvailability.UNAVAILABLE,
            ),
            transient = HistoryTransientUiState(),
            onOpen = {}, onOpenRequestShown = {}, onOpenLaunchFailed = {},
            onPlay = {}, onPlaybackLaunched = {}, onPlayMedia = {},
            onRemoveRecord = {}, onDeleteFileAndRecord = {},
            onClearFailed = {}, onClearCompleted = {}, onRetryLoad = {},
            onMessageShown = {},
        )
    }
}
