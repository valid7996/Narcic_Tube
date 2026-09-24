package com.narcictub.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.narcictub.app.data.ytdlp.YtDlpCookies
import com.narcictub.app.domain.model.AppSettings
import com.narcictub.app.domain.model.DownloadLocation
import com.narcictub.app.domain.model.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * PHASE 13 — Settings: Appearance (theme), Downloads (location + concurrent
 * limit). Values come from the DataStore-backed repository through use
 * cases and persist across restarts; the theme selection drives the REAL
 * app theme. Loading and error states are safe — no crashes, no raw
 * exceptions. Only settings with real consumers are shown; the model's
 * wifi-only/notifications fields are intentionally not exposed here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = {
            CenterAlignedTopAppBar(title = { Text("Settings") })
        },
    ) { padding ->
        if (state.isLoading) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.errorMessage?.let { message ->
                SettingsErrorBanner(message)
            }

            SectionHeader("Appearance")
            ThemeSection(
                selected = state.themeMode,
                onSelect = viewModel::onThemeModeSelected,
            )

            SectionHeader("Downloads")
            DownloadLocationSection(
                selected = state.downloadLocation,
                onSelect = viewModel::onDownloadLocationSelected,
            )
            CustomFolderSection(
                customFolderUri = state.customFolderUri,
                onFolderPicked = viewModel::onCustomFolderSelected,
            )
            ConcurrentDownloadsSection(
                value = state.concurrentDownloads,
                onSelect = viewModel::onConcurrentDownloadsSelected,
            )

            SectionHeader("YouTube & Instagram login (optional)")
            LoginCookiesSection()

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

/** Theme: three mutually exclusive modes in a segmented row. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeSection(
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
) {
    Text(
        text = "Theme",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(bottom = 8.dp),
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        val modes = ThemeMode.entries
        modes.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = selected == mode,
                onClick = { onSelect(mode) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                label = { Text(themeLabel(mode)) },
            )
        }
    }
    Text(
        text = "Applied immediately across the app.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/** Download location: real persisted enum, shown as a radio group. */
@Composable
private fun DownloadLocationSection(
    selected: DownloadLocation,
    onSelect: (DownloadLocation) -> Unit,
) {
    Text(
        text = "Download location",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(bottom = 8.dp),
    )
    DownloadLocation.entries.forEach { location ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectable(
                    selected = selected == location,
                    role = Role.RadioButton,
                    onClick = { onSelect(location) },
                )
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected == location, onClick = null)
            Text(
                text = locationLabel(location),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

/**
 * Custom save folder: a user-picked SAF document tree that overrides the
 * preset location above. The persistable grant is taken here — the picker
 * result only stays usable across restarts with it — and released again
 * when the folder is removed. Writes go through the view model; a failed
 * persist shows the shared error banner.
 */
@Composable
private fun CustomFolderSection(
    customFolderUri: String?,
    onFolderPicked: (String?) -> Unit,
) {
    val context = LocalContext.current

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            onFolderPicked(uri.toString())
        }
    }

    Text(
        text = "Custom folder",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
    )
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                text = customFolderUri?.let { folderDisplayName(it) }
                    ?: "Off — files follow the location above.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (customFolderUri != null) {
                Text(
                    text = "New downloads are saved here, overriding the location above.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Row(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = { picker.launch(null) }) {
                    Text(if (customFolderUri == null) "Choose folder…" else "Change folder…")
                }
                if (customFolderUri != null) {
                    TextButton(onClick = {
                        runCatching {
                            context.contentResolver.releasePersistableUriPermission(
                                Uri.parse(customFolderUri),
                                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                            )
                        }
                        onFolderPicked(null)
                    }) {
                        Text("Remove")
                    }
                }
            }
        }
    }
}

/** Human-readable folder name from a SAF tree URI ("primary:Music/Foo" → "Music/Foo"). */
private fun folderDisplayName(uriText: String): String =
    runCatching {
        val treeId = DocumentsContract.getTreeDocumentId(Uri.parse(uriText))
        treeId.substringAfter(':', treeId).ifBlank { "Selected folder" }
    }.getOrDefault("Selected folder")

/** Concurrent downloads: bounded slider; out-of-range values are impossible. */
@Composable
private fun ConcurrentDownloadsSection(
    value: Int,
    onSelect: (Int) -> Unit,
) {
    // Local drag value so DataStore is written once per gesture, not per tick.
    var dragging by remember(value) { mutableFloatStateOf(value.toFloat()) }

    Text(
        text = "Concurrent downloads",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
    )
    Slider(
        value = dragging,
        onValueChange = { dragging = it },
        onValueChangeFinished = { onSelect(dragging.toInt()) },
        valueRange = AppSettings.MIN_CONCURRENT_DOWNLOADS.toFloat()..AppSettings.MAX_CONCURRENT_DOWNLOADS.toFloat(),
        steps = AppSettings.MAX_CONCURRENT_DOWNLOADS - AppSettings.MIN_CONCURRENT_DOWNLOADS - 1,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        text = "Up to $value download${if (value == 1) "" else "s"} run at the same time.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Optional login session for yt-dlp. Some Instagram posts and age-restricted
 * or bot-checked YouTube videos can only be fetched by a logged-in browser;
 * importing that browser's cookies.txt (Netscape format) lets the downloader
 * do the same. The file stays in app-private storage and is never backed up.
 */
@Composable
private fun LoginCookiesSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var hasCookies by remember { mutableStateOf(YtDlpCookies.exists(context)) }
    var status by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val imported = withContext(Dispatchers.IO) { YtDlpCookies.import(context, uri) }
                hasCookies = YtDlpCookies.exists(context)
                status = if (imported) {
                    "Cookies imported."
                } else {
                    "That file doesn't look like a cookies.txt (Netscape format)."
                }
            }
        }
    }

    Text(
        text = "Export your browser cookies to a cookies.txt file and import it here. " +
            "It stays on this device and is only used for YouTube and Instagram downloads.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = { picker.launch(arrayOf("*/*")) }) {
            Text(if (hasCookies) "Replace cookies.txt" else "Import cookies.txt")
        }
        if (hasCookies) {
            TextButton(
                onClick = {
                    YtDlpCookies.clear(context)
                    hasCookies = false
                    status = "Cookies removed."
                },
            ) {
                Text("Remove")
            }
        }
    }
    status?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingsErrorBanner(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(12.dp),
        )
    }
}

private fun themeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> "System"
    ThemeMode.DARK -> "Dark"
    ThemeMode.LIGHT -> "Light"
}

private fun locationLabel(location: DownloadLocation): String = when (location) {
    DownloadLocation.DOWNLOADS -> "Downloads"
    DownloadLocation.MUSIC -> "Music"
    DownloadLocation.MOVIES -> "Movies"
    DownloadLocation.DCIM -> "DCIM (Camera)"
}
