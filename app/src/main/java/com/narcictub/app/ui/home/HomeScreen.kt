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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.narcictub.app.ui.theme.NarcicTubTheme

/**
 * Home screen: URL input, validation feedback, resolve + queue-download
 * actions. All business rules run in use cases; this is presentation only.
 */
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    HomeContent(
        state = state,
        onUrlChange = viewModel::onUrlChange,
        onClear = viewModel::onClear,
        onResolve = viewModel::onResolve,
        onDownload = viewModel::onDownload,
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
            text = "Paste a link to begin",
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
                enabled = state.isUrlValid && !state.isDownloading,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.Download, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (state.isDownloading) "Queuing…" else "Download")
            }
        }

        if (state.isResolving) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
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

        state.resolvedHost?.let { host ->
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "Resolved",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = host,
                        style = MaterialTheme.typography.bodyMedium,
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

@Preview(showBackground = true)
@Composable
private fun HomeContentEmptyPreview() {
    NarcicTubTheme {
        HomeContent(
            state = HomeUiState(),
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
            onUrlChange = {},
            onClear = {},
            onResolve = {},
            onDownload = {},
            onQueuedMessageShown = {},
        )
    }
}
