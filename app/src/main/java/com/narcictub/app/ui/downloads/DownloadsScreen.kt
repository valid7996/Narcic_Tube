package com.narcictub.app.ui.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.narcictub.app.domain.model.DownloadProgress
import com.narcictub.app.domain.model.DownloadStatus
import com.narcictub.app.domain.model.HistoryItem
import com.narcictub.app.ui.theme.NarcicTubTheme
import java.time.Instant

/**
 * Downloads screen — real state only. Empty when empty; active downloads
 * show real byte progress (indeterminate when total size is unknown);
 * cancel is wired to the actual download job.
 */
@Composable
fun DownloadsScreen(
    modifier: Modifier = Modifier,
    viewModel: DownloadsViewModel = hiltViewModel(),
) {
    val overview by viewModel.overview.collectAsStateWithLifecycle()
    val cancelError by viewModel.cancelError.collectAsStateWithLifecycle()

    DownloadsContent(
        overview = overview,
        onCancel = viewModel::onCancel,
        modifier = modifier,
    )

    cancelError?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

@Composable
private fun DownloadsContent(
    overview: com.narcictub.app.domain.model.DownloadsOverview,
    onCancel: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = overview.items
    if (items.isEmpty()) {
        Column(
            modifier = modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "No downloads yet",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items, key = { it.id }) { item ->
            DownloadRow(
                item = item,
                progress = overview.progress[item.id],
                onCancel = { onCancel(item.id) },
            )
        }
    }
}

@Composable
private fun DownloadRow(
    item: HistoryItem,
    progress: DownloadProgress?,
    onCancel: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusIcon(item.status)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    StatusLine(item, progress)
                }
                if (item.status == DownloadStatus.QUEUED || item.status == DownloadStatus.DOWNLOADING) {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Filled.Close, contentDescription = "Cancel download")
                    }
                }
            }

            if (item.status == DownloadStatus.DOWNLOADING) {
                val fraction = progress?.fraction
                if (fraction != null) {
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun StatusIcon(status: DownloadStatus) {
    when (status) {
        DownloadStatus.QUEUED -> Icon(
            Icons.Filled.HourglassTop,
            contentDescription = "Queued",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DownloadStatus.DOWNLOADING -> Icon(
            Icons.Filled.HourglassTop,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
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
        DownloadStatus.CANCELLED, DownloadStatus.PAUSED -> Icon(
            Icons.Filled.Close,
            contentDescription = "Cancelled",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatusLine(item: HistoryItem, progress: DownloadProgress?) {
    val text = when (item.status) {
        DownloadStatus.QUEUED -> "Queued"
        DownloadStatus.DOWNLOADING -> {
            val p = progress
            if (p == null) "Starting…" else formatBytes(p.downloadedBytes, p.totalBytes)
        }
        DownloadStatus.COMPLETED -> "Completed · ${formatBytes(item.sizeBytes, null)}"
        DownloadStatus.FAILED -> item.errorMessage ?: "Failed"
        DownloadStatus.CANCELLED -> "Cancelled"
        DownloadStatus.PAUSED -> "Paused"
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

private fun formatBytes(downloaded: Long, total: Long?): String {
    val d = humanBytes(downloaded)
    return if (total != null && total > 0) "$d / ${humanBytes(total)}" else d
}

private fun humanBytes(bytes: Long): String = when {
    bytes >= 1 shl 30 -> "%.1f GB".format(bytes / 1e9)
    bytes >= 1 shl 20 -> "%.1f MB".format(bytes / 1e6)
    bytes >= 1 shl 10 -> "%.0f KB".format(bytes / 1e3)
    else -> "$bytes B"
}

@Preview(showBackground = true)
@Composable
private fun DownloadsEmptyPreview() {
    NarcicTubTheme {
        DownloadsContent(
            overview = com.narcictub.app.domain.model.DownloadsOverview(),
            onCancel = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun DownloadsActivePreview() {
    NarcicTubTheme {
        DownloadsContent(
            overview = com.narcictub.app.domain.model.DownloadsOverview(
                items = listOf(
                    HistoryItem(
                        id = 1,
                        sourceUrl = "https://example.com/a",
                        title = "Sample video file",
                        status = DownloadStatus.DOWNLOADING,
                        createdAt = Instant.now(),
                    ),
                    HistoryItem(
                        id = 2,
                        sourceUrl = "https://example.com/b",
                        title = "Done file",
                        status = DownloadStatus.COMPLETED,
                        sizeBytes = 2_500_000,
                        createdAt = Instant.now(),
                    ),
                ),
                progress = mapOf(
                    1L to DownloadProgress(downloadedBytes = 700_000, totalBytes = 2_100_000),
                ),
            ),
            onCancel = {},
        )
    }
}
