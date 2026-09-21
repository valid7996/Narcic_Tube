package com.narcictub.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.narcictub.app.domain.model.MediaInfo
import com.narcictub.app.domain.model.MediaVariant
import com.narcictub.app.ui.theme.NarcicTubTheme
import java.util.Locale

/**
 * Home screen: URL input, validation feedback, resolve + queue-download
 * actions. All business rules run in use cases; this is presentation only.
 *
 * The resolved-media card renders ONLY what the resolver genuinely found —
 * unknown title/format/size/quality are left out, never replaced by a
 * placeholder. No URL, query string or path is ever displayed.
 */
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // PHASE 17: consume one-shot Android-Share intake events. The intake
    // ViewModel is activity-scoped so the event survives tab switches and
    // rotation, and is consumed exactly once (no duplicate resolve after
    // recreation). Sharing never auto-downloads — the user still chooses.
    val shareViewModel: ShareIntakeViewModel = hiltViewModel(
        viewModelStoreOwner = LocalContext.current as ViewModelStoreOwner,
    )
    val pendingShare by shareViewModel.pending.collectAsStateWithLifecycle()
    LaunchedEffect(pendingShare) {
        when (val share = pendingShare) {
            is PendingShare.Url -> {
                viewModel.onSharedUrlReceived(share.url)
                shareViewModel.onConsumed()
            }
            is PendingShare.Invalid -> {
                viewModel.onShareRejected(share.message)
                shareViewModel.onConsumed()
            }
            null -> Unit
        }
    }

    HomeContent(
        state = state,
        onUrlChange = viewModel::onUrlChange,
        onClear = viewModel::onClear,
        onResolve = viewModel::onResolve,
        onDownload = viewModel::onDownload,
        onVariantSelected = viewModel::onVariantSelected,
        onQueuedMessageShown = viewModel::onQueuedMessageShown,
        modifier = modifier,
    )
}

@Composable
private fun HomeContent(
    state: HomeUiState,
    onUrlChange: (String) -> Unit,
    onClear: () -> Unit,
    onResolve: () -> Unit,
    onDownload: () -> Unit,
    onVariantSelected: (String) -> Unit,
    onQueuedMessageShown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    var queuedVisible by remember { mutableStateOf(false) }

    LaunchedEffect(state.queuedSuccessfully) {
        if (state.queuedSuccessfully) {
            queuedVisible = true
            onQueuedMessageShown()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "NarcicTub",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = "Paste a direct media link to begin",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = state.url,
            onValueChange = onUrlChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Video or audio link") },
            placeholder = { Text("https://…") },
            leadingIcon = { Icon(Icons.Filled.Link, contentDescription = null) },
            trailingIcon = {
                if (state.url.isNotEmpty()) {
                    IconButton(onClick = onClear) {
                        Icon(Icons.Filled.Clear, contentDescription = "Clear")
                    }
                }
            },
            supportingText = {
                state.validationMessage?.let { Text(it) }
            },
            isError = state.validationMessage != null && state.url.isNotEmpty(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go,
            ),
            keyboardActions = KeyboardActions(onGo = { onDownload() }),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = { clipboard.getText()?.text?.let(onUrlChange) },
            ) {
                Icon(Icons.Filled.ContentPaste, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Paste")
            }
            OutlinedButton(
                onClick = onResolve,
                enabled = state.isUrlValid && !state.isResolving,
            ) {
                Text(if (state.isResolving) "Resolving…" else "Resolve")
            }
            Button(
                onClick = onDownload,
                // Gated on a genuine direct-file resolution — never enabled
                // on an unresolved or unsupported link.
                enabled = state.canDownload && !state.isDownloading,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.Download, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (state.isDownloading) "Queuing…" else "Download")
            }
        }

        if (state.isResolving) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else if (state.isUrlValid && state.resolvedMedia == null && state.errorMessage == null) {
            Text(
                text = "Paste a YouTube, Instagram or direct media link, then resolve it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        state.resolvedMedia?.let { info ->
            ResolvedMediaCard(
                info = info,
                displayVariants = state.displayVariants,
                selectedVariantUrl = state.selectedVariantUrl,
                onSelectVariant = onVariantSelected,
            )
        }

        if (queuedVisible) {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "Added to downloads",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "Track it on the Downloads tab.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        state.errorMessage?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun ResolvedMediaCard(
    info: MediaInfo,
    displayVariants: List<MediaVariant>,
    selectedVariantUrl: String?,
    onSelectVariant: (String) -> Unit,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "Ready to download",
                style = MaterialTheme.typography.titleMedium,
            )
            // Only real metadata: title when the source provided one, the
            // host, and format/size/quality strictly when known.
            info.title?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                text = info.host,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            info.mimeType?.let {
                Text(
                    text = "Format: $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            info.sizeBytes?.let {
                Text(
                    text = "Size: ${formatBytes(it)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // PHASE 18: real container-derived duration and resolution —
            // hidden entirely when the media does not report them.
            info.durationSeconds?.let {
                Text(
                    text = "Duration: ${formatDuration(it)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val width = info.videoWidth
            val height = info.videoHeight
            if (width != null && height != null && width > 0 && height > 0) {
                Text(
                    text = "Resolution: ${width}×${height}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            info.qualityLabel?.let {
                Text(
                    text = "Quality: $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // PHASE 20: real variant selection — every genuinely available
            // variant is shown (deterministic order), the selection must be
            // explicit for multi-variant media, and missing metadata is
            // omitted rather than invented.
            when {
                displayVariants.isEmpty() -> Text(
                    text = "No downloadable variants are available for this media.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> {
                    if (displayVariants.size > 1) {
                        Text(
                            text = "Choose a quality:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    displayVariants.forEach { variant ->
                        val selected = variant.downloadUrl == selectedVariantUrl
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = selected,
                                    role = Role.RadioButton,
                                    onClick = { onSelectVariant(variant.downloadUrl) },
                                )
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selected, onClick = null)
                            Text(
                                text = variantLabel(variant),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Real-fields-only variant label for the selection list. Every part comes
 * from actual variant data; missing metadata is omitted, never invented.
 */
private fun variantLabel(variant: MediaVariant): String {
    val label = buildList {
        variant.qualityLabel?.let { add(it) }
        val width = variant.width
        val height = variant.height
        if (width != null && height != null && width > 0 && height > 0) add("${width}×${height}")
        variant.container?.let { add(it.uppercase(Locale.US)) }
        variant.mimeType?.let { add(it) }
        variant.sizeBytes?.let { add(formatBytes(it)) }
    }.joinToString(" · ")
    // An empty label means the variant carries no metadata at all — the
    // neutral wording stays honest without inventing a value.
    return label.ifEmpty { "Available variant" }
}

/** Real duration formatted (m:ss / h:mm:ss); never a fabricated value. */
private fun formatDuration(seconds: Long): String {
    val total = seconds.coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(Locale.US, h, m, s) else "%d:%02d".format(Locale.US, m, s)
}

/** Human-readable size from the real byte count; never invented. */
private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
    return String.format(Locale.US, "%.1f GB", mb / 1024.0)
}

@Preview(showBackground = true)
@Composable
private fun HomeContentEmptyPreview() {
    NarcicTubTheme {
        HomeContent(
            state = HomeUiState(),
            onVariantSelected = {},
            onUrlChange = {},
            onClear = {},
            onResolve = {},
            onDownload = {},
            onQueuedMessageShown = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeContentActivePreview() {
    NarcicTubTheme {
        HomeContent(
            state = HomeUiState(
                url = "https://example.com/watch?v=123",
                isUrlValid = true,
            ),
            onVariantSelected = {},
            onUrlChange = {},
            onClear = {},
            onResolve = {},
            onDownload = {},
            onQueuedMessageShown = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeContentResolvedPreview() {
    NarcicTubTheme {
        HomeContent(
            state = HomeUiState(
                url = "https://cdn.example.com/clip.mp4",
                isUrlValid = true,
                resolvedMedia = MediaInfo(
                    sourceUrl = "https://cdn.example.com/clip.mp4",
                    title = "clip.mp4",
                    host = "cdn.example.com",
                    mimeType = "video/mp4",
                    sizeBytes = 26_214_400,
                    isDirectFile = true,
                    downloadUrl = "https://cdn.example.com/clip.mp4",
                ),
            ),
            onVariantSelected = {},
            onUrlChange = {},
            onClear = {},
            onResolve = {},
            onDownload = {},
            onQueuedMessageShown = {},
        )
    }
}
