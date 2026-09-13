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
import androidx.compose.runtime.getValue
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
 * Home screen: URL input form with validation, paste support and a stub
 * resolve step. Real metadata fetching plugs into [HomeViewModel.onResolve].
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
        modifier = modifier,
    )
}

@Composable
private fun HomeContent(
    state: HomeUiState,
    onUrlChange: (String) -> Unit,
    onClear: () -> Unit,
    onResolve: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current

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
            keyboardActions = KeyboardActions(onGo = { onResolve() }),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = {
                    clipboard.getText()?.text?.let(onUrlChange)
                },
            ) {
                Icon(Icons.Filled.ContentPaste, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Paste")
            }
            Button(
                onClick = onResolve,
                enabled = state.isUrlValid && !state.isResolving,
                modifier = Modifier.weight(1f),
            ) {
                Text(if (state.isResolving) "Resolving…" else "Resolve")
            }
        }

        if (state.isResolving) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        state.resolvedHost?.let { host ->
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "Ready to download",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = host,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "Full title, formats and quality picker land with the data layer.",
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

@Preview(showBackground = true)
@Composable
private fun HomeContentEmptyPreview() {
    NarcicTubTheme {
        HomeContent(
            state = HomeUiState(),
            onUrlChange = {},
            onClear = {},
            onResolve = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeContentResolvedPreview() {
    NarcicTubTheme {
        HomeContent(
            state = HomeUiState(
                url = "https://example.com/watch?v=123",
                isUrlValid = true,
                resolvedHost = "example.com",
            ),
            onUrlChange = {},
            onClear = {},
            onResolve = {},
        )
    }
}
