package com.narcictub.app.ui.share

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import com.narcictub.app.ui.theme.honeyAccentTextColor
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The COMPACT share download sheet ("Download as") — a small floating dialog
 * that appears OVER the sharing app (YouTube → Share → NarcicTub). It shows
 * the resolved real formats (Music / Video, real sizes), queues the chosen
 * one on an explicit tap, and then offers two ways on: "Go to downloads"
 * (opens the app on the Downloads tab) or "OK" (closes the sheet — the user
 * stays in the sharing app while the download continues in the background).
 */
@Composable
fun ShareDownloadSheet(
    onGoToDownloads: () -> Unit,
    onDismiss: () -> Unit,
    state: ShareDownloadUiState,
    onVariantSelected: (String) -> Unit,
    onDownload: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // "Added to download queue" toast the moment the enqueue lands.
    LaunchedEffect(state.queued) {
        if (state.queued) {
            Toast.makeText(context, "Added to download queue", Toast.LENGTH_SHORT).show()
        }
    }

    Surface(
        modifier = modifier
            .widthIn(max = 340.dp)
            .heightIn(max = 520.dp),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            SheetHeader(
                title = state.resolvedMedia?.title,
                onDismiss = onDismiss,
            )

            Box(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .heightIn(max = 300.dp),
            ) {
                when {
                    state.isResolving -> ResolvingBody()
                    state.queued -> QueuedBody(
                        onGoToDownloads = onGoToDownloads,
                        onDismiss = onDismiss,
                    )
                    state.resolvedMedia == null -> ErrorBody(
                        message = state.errorMessage.orEmpty(),
                        canRetry = state.url != null,
                        onRetry = onRetry,
                    )
                    else -> {
                        Column(
                            modifier = Modifier.verticalScroll(rememberScrollState()),
                        ) {
                            state.errorMessage?.let { message ->
                                Text(
                                    text = message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            val rows = remember(state.resolvedMedia) {
                                buildFormatRows(state.resolvedMedia?.variants.orEmpty())
                            }
                            FormatListBody(
                                rows = rows,
                                selectedUrl = state.selectedVariantUrl,
                                onSelect = onVariantSelected,
                            )
                        }
                    }
                }
            }

            if (state.resolvedMedia != null && !state.queued) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Cancel = بستن شیت؛ کاربر در همان اپ می‌ماند
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp),
                        shape = RoundedCornerShape(22.dp),
                    ) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = onDownload,
                        enabled = state.canDownload,
                        modifier = Modifier
                            .weight(1.4f)
                            .height(44.dp),
                        shape = RoundedCornerShape(22.dp),
                    ) {
                        if (state.isEnqueueing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Text("Start Download")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetHeader(title: String?, onDismiss: () -> Unit) {
    Row(verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "SELECT QUALITY",
                style = MaterialTheme.typography.labelMedium,
                color = honeyAccentTextColor(),
            )
            Text(
                text = title ?: "Download as",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }
        IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Close",
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun ResolvingBody() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp, bottom = 24.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
        Text(
            text = "Fetching formats…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

@Composable
private fun ErrorBody(message: String, canRetry: Boolean, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (canRetry) {
            TextButton(onClick = onRetry) { Text("Try again") }
        }
    }
}

/** Post-enqueue confirmation: stay here, or jump to the Downloads tab. */
@Composable
private fun QueuedBody(onGoToDownloads: () -> Unit, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(40.dp),
        )
        Text(
            text = "Added to download queue",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = "The download continues in the background.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 8.dp),
        ) {
            TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                Text("OK")
            }
            Button(onClick = onGoToDownloads, modifier = Modifier.weight(1f)) {
                Text("Go to downloads")
            }
        }
    }
}

@Composable
private fun FormatListBody(
    rows: List<FormatRow>,
    selectedUrl: String?,
    onSelect: (String) -> Unit,
) {
    FormatGroup.entries.forEach { group ->
        val groupRows = rows.filter { it.group == group }
        if (groupRows.isEmpty()) return@forEach
        Text(
            text = group.label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
        )
        groupRows.forEach { row ->
            FormatRowItem(
                row = row,
                selected = row.variant.downloadUrl == selectedUrl,
                onSelect = onSelect,
            )
        }
    }
}

@Composable
private fun FormatRowItem(
    row: FormatRow,
    selected: Boolean,
    onSelect: (String) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = { onSelect(row.variant.downloadUrl) },
            ),
        shape = MaterialTheme.shapes.small,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = row.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = row.subtitle.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            RadioButton(selected = selected, onClick = null, modifier = Modifier.size(30.dp))
        }
    }
}

private val FormatRow.icon: ImageVector
    get() = when (group) {
        FormatGroup.MUSIC -> Icons.Outlined.MusicNote
        else -> Icons.Filled.PlayCircle
    }
