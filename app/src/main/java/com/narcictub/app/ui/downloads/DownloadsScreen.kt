package com.narcictub.app.ui.downloads

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.narcictub.app.domain.model.DownloadProgress
import com.narcictub.app.domain.model.DownloadStatus
import com.narcictub.app.domain.model.DownloadsOverview
import com.narcictub.app.domain.model.HistoryItem
import com.narcictub.app.ui.theme.BeeIcon
import com.narcictub.app.ui.theme.HexGauge
import com.narcictub.app.ui.theme.HexPointyShape
import com.narcictub.app.ui.theme.Hexagon
import com.narcictub.app.ui.theme.HiveTabShape
import com.narcictub.app.ui.theme.HoneyGradient
import com.narcictub.app.ui.theme.InkOnHoney
import com.narcictub.app.ui.theme.NarcicTubTheme
import com.narcictub.app.ui.theme.StripedHoneyBar
import com.narcictub.app.ui.theme.honeycomb
import java.time.Instant

/**
 * Downloads screen (Phase 7, extended in Phase 9): real queue state only.
 * Active downloads show real byte progress (indeterminate bar when
 * Content-Length is unknown); queued, finished, failed and cancelled rows
 * render from persisted state. Loading is distinguished from empty; failed
 * AND cancelled rows can be retried (the repository supports both);
 * completed rows show their real completion timestamp. Cancel/retry/remove
 * act through use cases on real rows by stable id. No simulated timers, no
 * fake progress, no sample items in production.
 */
@Composable
fun DownloadsScreen(
    modifier: Modifier = Modifier,
    viewModel: DownloadsViewModel = hiltViewModel(),
) {
    val overview by viewModel.overview.collectAsStateWithLifecycle()
    val transient by viewModel.transient.collectAsStateWithLifecycle()

    DownloadsScreenContent(
        overview = overview,
        transient = transient,
        onCancel = viewModel::onCancel,
        onRetry = viewModel::onRetry,
        onRemove = viewModel::onRemove,
        onClearFinished = viewModel::onRemoveCompletedConfirmed,
        onClearFailed = viewModel::onRemoveFailedConfirmed,
        onMessageShown = viewModel::onMessageShown,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreenContent(
    overview: DownloadsOverview,
    transient: DownloadsTransientUiState,
    onCancel: (Long) -> Unit,
    onRetry: (Long) -> Unit,
    onRemove: (Long) -> Unit,
    onClearFinished: () -> Unit,
    onClearFailed: () -> Unit,
    onMessageShown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = remember(overview) { overview.toUiState() }
    val snackbarHostState = remember { SnackbarHostState() }

    // One-shot transient feedback → snackbar, then reset via the ViewModel.
    val message = transient.errorMessage ?: transient.removedCountMessage
    LaunchedEffect(message) {
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            onMessageShown()
        }
    }

    var confirmClearFinished by remember { mutableStateOf(false) }
    var confirmClearFailed by remember { mutableStateOf(false) }

    /** Row pending destructive-removal confirmation (COMPLETED only). */
    var confirmRemoveId by remember { mutableStateOf<Long?>(null) }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Downloads") },
                actions = {
                    if (state.hasFinished) {
                        IconButton(onClick = { confirmClearFinished = true }) {
                            Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear finished")
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (state.showLoading) {
            // First snapshot has not arrived yet — a real loading state,
            // distinct from "genuinely no downloads" below.
            LoadingState(Modifier.padding(padding).fillMaxSize())
        } else if (state.isEmpty) {
            EmptyState(Modifier.padding(padding).fillMaxSize())
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.active.isNotEmpty() || state.queue.isNotEmpty() || state.finished.isNotEmpty()) {
                    item(key = "hive-stats") { HiveStatsRow(state) }
                }
                if (state.active.isNotEmpty() || state.queue.isNotEmpty()) {
                    item(key = "hive-folder") {
                        HiveFolder(
                            rows = state.active + state.queue,
                            onCancel = onCancel,
                        )
                    }
                }
                if (state.finished.isNotEmpty()) {
                    // Fix 1: "Clear failed" lives IN the finished section and
                    // is offered exactly while failed/cancelled records exist
                    // — it opens the existing confirmation dialog below.
                    item(key = "header-Finished") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Finished",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f),
                            )
                            if (state.hasFailed) {
                                TextButton(
                                    onClick = { confirmClearFailed = true },
                                    contentPadding = PaddingValues(horizontal = 8.dp),
                                ) { Text("Clear failed") }
                            }
                        }
                    }
                    items(state.finished, key = { "finished-${it.id}" }) { row ->
                        FinishedDownloadCard(
                            row = row,
                            onRetry = onRetry,
                            // Fix 2: only COMPLETED removals (which delete the
                            // published file) require confirmation; failed and
                            // cancelled rows hold no file and remove immediately.
                            onRemoveRequest = { target ->
                                if (requiresRemovalConfirmation(target.status)) {
                                    confirmRemoveId = target.id
                                } else {
                                    onRemove(target.id)
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    if (confirmClearFinished) {
        AlertDialog(
            onDismissRequest = { confirmClearFinished = false },
            title = { Text("Clear finished downloads?") },
            text = { Text("Removes all completed downloads and their published files. This can't be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClearFinished = false
                        onClearFinished()
                    },
                ) { Text("Clear", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearFinished = false }) { Text("Cancel") }
            },
        )
    }
    if (confirmClearFailed) {
        AlertDialog(
            onDismissRequest = { confirmClearFailed = false },
            title = { Text("Clear failed downloads?") },
            text = { Text("Removes failed and cancelled download records. This can't be undone.") },
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

    // Fix 2: destructive per-row removal — COMPLETED rows delete a published
    // file, so they confirm first. Failed/cancelled rows never reach this
    // dialog (no file exists for them). No paths or URIs are shown.
    if (confirmRemoveId != null) {
        AlertDialog(
            onDismissRequest = { confirmRemoveId = null },
            title = { Text("Remove download and delete its file?") },
            text = {
                Text(
                    "This removes the download from your list and permanently " +
                        "deletes its downloaded file. This can't be undone.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val targetId = confirmRemoveId
                        confirmRemoveId = null
                        if (targetId != null) onRemove(targetId)
                    },
                ) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemoveId = null }) { Text("Cancel") }
            },
        )
    }
}

private fun LazyListScope.sectionHeader(title: String) {
    item(key = "header-$title") {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 4.dp),
        )
    }
}

/** Active (downloading/paused) or queued row: title, host, live progress, cancel. */
@Composable
private fun ActiveDownloadCard(
    row: UiDownload,
    onCancel: (Long) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusIcon(status = row.status)
                Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = row.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        StatusBadge(status = row.status, modifier = Modifier.padding(start = 8.dp))
                    }
                    HostLine(host = row.host)
                    StatusLine(
                        status = row.status,
                        progress = row.progress,
                        errorMessage = row.errorMessage,
                        bytes = row.completedBytes,
                    )
                }
                HexGauge(
                    progress = row.progress?.fraction,
                    modifier = Modifier.padding(start = 8.dp),
                )
                if (offersCancelAction(row.status)) {
                    IconButton(onClick = { onCancel(row.id) }) {
                        Icon(Icons.Filled.Close, contentDescription = "Cancel download")
                    }
                }
            }
            if (row.status == DownloadStatus.DOWNLOADING || row.status == DownloadStatus.PAUSED) {
                // Real byte fraction when Content-Length is known; an honest
                // indeterminate stripe when it isn't — never an invented %.
                StripedHoneyBar(
                    fraction = row.progress?.fraction,
                    running = row.status == DownloadStatus.DOWNLOADING,
                    paused = row.status == DownloadStatus.PAUSED,
                )
            }
        }
    }
}

/**
 * HIVE stats — three interlocked hexagons (the middle one honey-filled and
 * layered above): in hive (active + queued) · running (transferring) ·
 * saved (completed records). All live, all real counts.
 */
@Composable
private fun HiveStatsRow(state: DownloadsUiState) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        StatHex(
            value = (state.active.size + state.queue.size).toString(),
            label = "in hive",
            filled = false,
        )
        StatHex(
            value = state.active.size.toString(),
            label = "running",
            filled = true,
            modifier = Modifier
                .offset(x = (-18).dp)
                .zIndex(1f),
        )
        StatHex(
            value = state.finished.count { it.status == DownloadStatus.COMPLETED }.toString(),
            label = "saved",
            filled = false,
            modifier = Modifier.offset(x = (-36).dp),
        )
    }
}

@Composable
private fun StatHex(value: String, label: String, filled: Boolean, modifier: Modifier = Modifier) {
    Hexagon(
        size = 88.dp,
        fill = if (filled) HoneyGradient else null,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.then(
            if (filled) Modifier else Modifier.border(1.5.dp, MaterialTheme.colorScheme.outlineVariant, HexPointyShape),
        ),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleLarge,
                    color = if (filled) InkOnHoney else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (filled) InkOnHoney.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The HIVE folder: a bordered card with the honeycomb texture inside and an
 * overhanging honey tab (bee + "In progress" + count pill) holding every
 * active and queued download.
 */
@Composable
private fun HiveFolder(rows: List<UiDownload>, onCancel: (Long) -> Unit) {
    Box(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = 14.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .border(1.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                .honeycomb(MaterialTheme.colorScheme.primary, alpha = 0.08f, tile = 56.dp)
                .padding(top = 26.dp, start = 10.dp, end = 10.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            rows.forEach { row -> ActiveDownloadCard(row = row, onCancel = onCancel) }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(y = (-14).dp)
                .clip(HiveTabShape)
                .background(HoneyGradient)
                .padding(start = 12.dp, end = 24.dp, top = 7.dp, bottom = 7.dp),
        ) {
            val flap by rememberInfiniteTransition(label = "hiveBee").animateFloat(
                -18f, 18f, infiniteRepeatable(tween(120), RepeatMode.Reverse), label = "flap",
            )
            BeeIcon(modifier = Modifier.size(26.dp), flapAngle = flap)
            Text(
                text = " In progress",
                style = MaterialTheme.typography.labelMedium,
                color = InkOnHoney,
            )
            Box(
                modifier = Modifier
                    .padding(start = 8.dp)
                    .clip(CircleShape)
                    .background(InkOnHoney.copy(alpha = 0.20f))
                    .padding(horizontal = 7.dp, vertical = 1.dp),
            ) {
                Text(
                    text = rows.size.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = InkOnHoney,
                )
            }
        }
    }
}

/** Finished row: completed (confirmed removal), failed (retry/remove) or cancelled (remove). */
@Composable
private fun FinishedDownloadCard(
    row: UiDownload,
    onRetry: (Long) -> Unit,
    onRemoveRequest: (UiDownload) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusIcon(status = row.status)
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = row.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    StatusBadge(status = row.status, modifier = Modifier.padding(start = 8.dp))
                }
                HostLine(host = row.host)
                StatusLine(
                    status = row.status,
                    progress = row.progress,
                    errorMessage = row.errorMessage,
                    bytes = row.completedBytes,
                )
                // Phase 9: real persisted completion time — only for rows
                // that actually completed.
                if (row.status == DownloadStatus.COMPLETED) {
                    row.completedAtEpochMs?.let { CompletedLine(it) }
                }
            }
            if (offersRetryAction(row.status)) {
                // Phase 9: the repository re-queues FAILED and CANCELLED rows
                // alike — the affordance now matches that real capability.
                IconButton(onClick = { onRetry(row.id) }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Retry download")
                }
            }
            // Accessibility label states the real effect: removing a
            // COMPLETED row also deletes its published file.
            IconButton(onClick = { onRemoveRequest(row) }) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = if (requiresRemovalConfirmation(row.status)) {
                        "Remove download and delete its file"
                    } else {
                        "Remove from list"
                    },
                )
            }
        }
    }
}

/** Tinted circular icon well — the quiet visual anchor of every row. */
@Composable
private fun StatusIcon(status: DownloadStatus) {
    val (vector, tint) = statusIconFor(status)
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = vector,
            contentDescription = statusAccessibilityLabel(status),
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun statusIconFor(status: DownloadStatus): Pair<ImageVector, Color> = when (status) {
    DownloadStatus.QUEUED -> Icons.Filled.HourglassTop to MaterialTheme.colorScheme.onSurfaceVariant
    DownloadStatus.DOWNLOADING -> Icons.Filled.Downloading to MaterialTheme.colorScheme.primary
    DownloadStatus.PAUSED -> Icons.Filled.Close to MaterialTheme.colorScheme.onSurfaceVariant
    DownloadStatus.COMPLETED -> Icons.Filled.DownloadDone to MaterialTheme.colorScheme.primary
    DownloadStatus.FAILED -> Icons.Filled.ErrorOutline to MaterialTheme.colorScheme.error
    DownloadStatus.CANCELLED -> Icons.Filled.Close to MaterialTheme.colorScheme.onSurfaceVariant
}

private fun statusAccessibilityLabel(status: DownloadStatus): String = when (status) {
    DownloadStatus.QUEUED -> "Queued"
    DownloadStatus.DOWNLOADING -> "Downloading"
    DownloadStatus.PAUSED -> "Paused"
    DownloadStatus.COMPLETED -> "Completed"
    DownloadStatus.FAILED -> "Failed"
    DownloadStatus.CANCELLED -> "Cancelled"
}

/** Small colored status pill next to the row title. */
@Composable
private fun StatusBadge(status: DownloadStatus, modifier: Modifier = Modifier) {
    val (label, container, content) = when (status) {
        DownloadStatus.QUEUED -> Triple(
            "Queued",
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
        )
        DownloadStatus.DOWNLOADING -> Triple(
            "Downloading",
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
        )
        DownloadStatus.PAUSED -> Triple(
            "Paused",
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
        )
        DownloadStatus.COMPLETED -> Triple(
            "Done",
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
        )
        DownloadStatus.FAILED -> Triple(
            "Failed",
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
        )
        DownloadStatus.CANCELLED -> Triple(
            "Cancelled",
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = container,
        modifier = modifier,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = content,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/** Source host only — never the full URL (paths/queries can be sensitive). */
@Composable
private fun HostLine(host: String?) {
    if (host != null) {
        Text(
            text = host,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun StatusLine(
    status: DownloadStatus,
    progress: DownloadProgress?,
    errorMessage: String?,
    bytes: Long,
) {
    val text = when (status) {
        DownloadStatus.QUEUED -> "Queued"
        DownloadStatus.DOWNLOADING -> {
            val p = progress
            if (p == null) "Starting…" else formatBytes(p.downloadedBytes, p.totalBytes)
        }
        DownloadStatus.PAUSED -> "Paused"
        DownloadStatus.COMPLETED -> if (bytes > 0) formatBytes(bytes, null) else "Completed"
        DownloadStatus.FAILED -> errorMessage ?: "Failed"
        DownloadStatus.CANCELLED -> "Cancelled"
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Downloading,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp),
            )
        }
        Text(
            text = "No downloads yet",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 20.dp),
        )
        Text(
            text = "Share a link into NarcicTub or paste one on the Home tab.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp, start = 32.dp, end = 32.dp),
        )
    }
}

/** Pre-first-emission state — shown only while no snapshot has arrived. */
@Composable
private fun LoadingState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text(
            text = "Loading downloads…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

/** Real persisted completion time; only rendered for COMPLETED rows. */
@Composable
private fun CompletedLine(epochMs: Long) {
    Text(
        text = "Completed " + formatCompletedDateTime(epochMs),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Local date/time of the persisted completion instant (real data only). */
internal fun formatCompletedDateTime(epochMs: Long): String =
    java.time.format.DateTimeFormatter
        .ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM, java.time.format.FormatStyle.SHORT)
        .withZone(java.time.ZoneId.systemDefault())
        .format(java.time.Instant.ofEpochMilli(epochMs))

internal fun formatBytes(downloaded: Long, total: Long?): String {
    val d = humanBytes(downloaded)
    return if (total != null && total > 0) "$d / ${humanBytes(total)}" else d
}

internal fun humanBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / 1e9)
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / 1e6)
    bytes >= 1L shl 10 -> "%.0f KB".format(bytes / 1e3)
    else -> "$bytes B"
}

@Preview(showBackground = true)
@Composable
private fun DownloadsEmptyPreview() {
    NarcicTubTheme {
        DownloadsScreenContent(
            // Genuinely empty (a snapshot arrived with zero rows).
            overview = DownloadsOverview(isLoading = false),
            transient = DownloadsTransientUiState(),
            onCancel = {}, onRetry = {}, onRemove = {},
            onClearFinished = {}, onClearFailed = {}, onMessageShown = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun DownloadsLoadingPreview() {
    NarcicTubTheme {
        DownloadsScreenContent(
            // No snapshot yet — loading, not empty.
            overview = DownloadsOverview(),
            transient = DownloadsTransientUiState(),
            onCancel = {}, onRetry = {}, onRemove = {},
            onClearFinished = {}, onClearFailed = {}, onMessageShown = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun DownloadsActivePreview() {
    NarcicTubTheme {
        DownloadsScreenContent(
            overview = DownloadsOverview(
                items = listOf(
                    HistoryItem(
                        id = 1,
                        sourceUrl = "https://cdn.example.com/video.mp4",
                        title = "video.mp4",
                        status = DownloadStatus.DOWNLOADING,
                        createdAt = Instant.now(),
                    ),
                    HistoryItem(
                        id = 2,
                        sourceUrl = "https://cdn.example.com/audio.zip",
                        title = "audio.zip",
                        status = DownloadStatus.QUEUED,
                        createdAt = Instant.now(),
                    ),
                    HistoryItem(
                        id = 3,
                        sourceUrl = "https://cdn.example.com/old.mp4",
                        title = "old.mp4",
                        status = DownloadStatus.COMPLETED,
                        sizeBytes = 2_500_000,
                        localUri = "content://media/external/downloads/1",
                        createdAt = Instant.now(),
                        completedAt = Instant.now(),
                    ),
                    HistoryItem(
                        id = 4,
                        sourceUrl = "https://cdn.example.com/gone.mp4",
                        title = "gone.mp4",
                        status = DownloadStatus.FAILED,
                        errorMessage = "HTTP error 404",
                        createdAt = Instant.now(),
                    ),
                ),
                progress = mapOf(
                    1L to DownloadProgress(downloadedBytes = 700_000, totalBytes = 2_100_000),
                ),
            ),
            transient = DownloadsTransientUiState(),
            onCancel = {}, onRetry = {}, onRemove = {},
            onClearFinished = {}, onClearFailed = {}, onMessageShown = {},
        )
    }
}
