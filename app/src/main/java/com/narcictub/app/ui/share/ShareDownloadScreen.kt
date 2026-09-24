package com.narcictub.app.ui.share

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.narcictub.app.ui.common.ResolveErrorMessages

/**
 * The Share download screen ("Download as") — what opens when a video link
 * is shared into NarcicTub (e.g. YouTube → Share → NarcicTub). It resolves
 * the shared link into its REAL format variants and lets the user pick one
 * (music or video, with real sizes when known) and queue the download with
 * one explicit tap. Nothing downloads automatically.
 */
@Composable
fun ShareDownloadScreen(
    onQueued: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ShareDownloadViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // One-shot: the variant was queued — route to the Downloads tab where
    // the live progress is visible.
    LaunchedEffect(state.queued) {
        if (state.queued) onQueued()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        // Sheet-style grip handle, echoing the system share-sheet look.
        Box(
            modifier = Modifier
                .padding(top = 12.dp, bottom = 8.dp)
                .width(40.dp)
                .height(4.dp)
                .background(
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(2.dp),
                )
                .align(Alignment.CenterHorizontally),
        )

        Text(
            text = "Download as",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        val title = state.resolvedMedia?.title
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        when {
            state.isResolving -> ResolvingBody()
            state.resolvedMedia == null && state.errorMessage != null -> ErrorBody(
                message = state.errorMessage.orEmpty(),
                canRetry = state.url != null,
                onRetry = viewModel::retry,
            )
            state.resolvedMedia != null -> {
                if (state.errorMessage != null) {
                    Text(
                        text = state.errorMessage.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                val rows = remember(state.resolvedMedia) {
                    buildFormatRows(state.resolvedMedia?.variants.orEmpty())
                }
                FormatListBody(
                    rows = rows,
                    selectedUrl = state.selectedVariantUrl,
                    onSelect = viewModel::onVariantSelected,
                )
            }
        }

        Spacer(Modifier.weight(1f))

        if (state.resolvedMedia != null) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Button(
                onClick = viewModel::onDownloadSelected,
                enabled = state.canDownload,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp)
                    .height(52.dp),
                shape = RoundedCornerShape(26.dp),
                colors = ButtonDefaults.buttonColors(),
            ) {
                if (state.isEnqueueing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text(
                        text = "Download",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun ResolvingBody() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text(
            text = "Fetching available formats…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

@Composable
private fun ErrorBody(
    message: String,
    canRetry: Boolean,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (canRetry) {
            TextButton(onClick = onRetry, modifier = Modifier.padding(top = 8.dp)) {
                Text("Try again")
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
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = { onSelect(row.variant.downloadUrl) },
            )
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = row.icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(28.dp),
        )
        Text(
            text = row.label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .padding(start = 16.dp)
                .weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        row.sizeText?.let { size ->
            Text(
                text = size,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 12.dp),
            )
        }
        RadioButton(selected = selected, onClick = null)
    }
}

private val FormatRow.icon: ImageVector
    get() = when (group) {
        FormatGroup.MUSIC -> Icons.Outlined.MusicNote
        else -> Icons.Filled.PlayCircle
    }
