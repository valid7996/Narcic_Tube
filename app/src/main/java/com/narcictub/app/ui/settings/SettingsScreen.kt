package com.narcictub.app.ui.settings

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.narcictub.app.domain.model.AppSettings
import com.narcictub.app.domain.model.DownloadLocation
import com.narcictub.app.domain.model.ThemeMode

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
            ConcurrentDownloadsSection(
                value = state.concurrentDownloads,
                onSelect = viewModel::onConcurrentDownloadsSelected,
            )

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
