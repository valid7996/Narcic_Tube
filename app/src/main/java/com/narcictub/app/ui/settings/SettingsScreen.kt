package com.narcictub.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.narcictub.app.data.ytdlp.YtDlpCookies
import com.narcictub.app.domain.model.AppSettings
import com.narcictub.app.domain.model.ThemeMode
import com.narcictub.app.ui.theme.Hexagon
import com.narcictub.app.ui.theme.HoneyGradient
import com.narcictub.app.ui.theme.honeyAccentTextColor
import com.narcictub.app.ui.theme.honeycomb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * PHASE 13 — Settings, honey-styled: two grouped cards ("Appearance &
 * Theme", "Download Settings") with compact title/subtitle/action rows.
 * Values come from the DataStore-backed repository through use cases and
 * persist across restarts; the theme selection drives the REAL app theme.
 * Loading and error states are safe — no crashes, no raw exceptions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    onOpenStatuses: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showInstagramLogin by remember { mutableStateOf(false) }
    var cookiesEpoch by remember { mutableStateOf(0) }

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
                .honeycomb(MaterialTheme.colorScheme.primary, alpha = 0.05f, tile = 72.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            state.errorMessage?.let { message ->
                SettingsErrorBanner(message)
            }

            // ─── APPEARANCE & THEME ───
            SettingsCard("Appearance & Theme") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "Color Theme",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "Warm honey palette",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    SingleChoiceSegmentedButtonRow(Modifier.width(200.dp)) {
                        val modes = ThemeMode.entries
                        modes.forEachIndexed { index, mode ->
                            SegmentedButton(
                                selected = state.themeMode == mode,
                                onClick = { viewModel.onThemeModeSelected(mode) },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                                label = { Text(themeLabel(mode)) },
                            )
                        }
                    }
                }
            }

            // ─── DOWNLOAD SETTINGS ───
            SettingsCard("Download Settings") {
                Column {
                    StorageLocationRow(
                        customFolderUri = state.customFolderUri,
                        onFolderPicked = viewModel::onCustomFolderSelected,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ConcurrentDownloadsRow(
                        value = state.concurrentDownloads,
                        onSelect = viewModel::onConcurrentDownloadsSelected,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    CookiesRow(epoch = cookiesEpoch, onShowLogin = { showInstagramLogin = true })
                }
            }

            // ─── WHATSAPP STATUS ───
            SettingsCard("WhatsApp Status") {
                Column {
                    val context = LocalContext.current
                    val waFolder = state.whatsappStatusFolderUri
                    val waGrant = waFolder?.let { uriText ->
                        context.contentResolver.persistedUriPermissions.any {
                            it.uri.toString() == uriText && (it.isReadPermission || it.isWritePermission)
                        }
                    } == true

                    // پیکر پوشه استوری‌ها: grant خواندن برای فهرست‌کردن فایل‌ها
                    val waPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                        if (uri != null) {
                            runCatching {
                                context.contentResolver.takePersistableUriPermission(
                                    uri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                                )
                            }
                            viewModel.onWhatsappFolderSelected(uri.toString())
                        }
                    }

                    SettingRow(
                        title = "Statuses folder",
                        subtitle = when {
                            waFolder == null -> "Not set — pick WhatsApp → Media → .Statuses once"
                            waGrant -> folderDisplayName(waFolder) + "  ·  access granted"
                            else -> "Access revoked — pick the folder again"
                        },
                        action = {
                            OutlinedPill(text = if (waFolder == null || !waGrant) "Choose" else "Change") {
                                // راهنمای مکان‌یابی: مستقیم به پوشه استوری‌های واتساپ
                                val hint = DocumentsContract.buildDocumentUri(
                                    "com.android.externalstorage.documents",
                                    "primary:Android/media/com.whatsapp/WhatsApp/Media/.Statuses",
                                )
                                waPicker.launch(hint)
                            }
                        },
                    )
                    if (waFolder != null && waGrant) {
                        SettingRow(
                            title = "View statuses",
                            subtitle = "Photos and videos currently in that folder",
                            action = { OutlinedPill(text = "Open") { onOpenStatuses() } },
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }

    if (showInstagramLogin) {
        InstagramLoginDialog(
            onSessionSaved = { ok -> cookiesEpoch++ },
            onDismiss = { showInstagramLogin = false },
        )
    }
}

/** Grouped honey card: hexagon-bullet header INSIDE the card, content below. */
@Composable
private fun SettingsCard(header: String, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 16.dp, top = 14.dp),
            ) {
                Hexagon(size = 10.dp, fill = HoneyGradient)
                Text(
                    text = header.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = honeyAccentTextColor(),
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
            content()
        }
    }
}

/** Compact setting row: title + subtitle on the left, action on the right. */
@Composable
private fun SettingRow(
    title: String,
    subtitle: String,
    action: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        action()
    }
}

/** Outlined pill button ("Change" / "Import" / …). */
@Composable
private fun OutlinedPill(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(999.dp))
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Storage location row. Default is the app-named folder inside the shared
 * Downloads collection ("Downloads/NarcicTub"); "Change" opens the SAF
 * folder picker and takes a persistable grant; "Default" clears an active
 * custom folder again (releasing the grant).
 *
 * PERMISSION STATE: the SAF grant is the permission that keeps the chosen
 * location working across restarts. If the platform revoked it (app data
 * cleared, folder removed), the row says so and offers "Re-grant" instead
 * of pretending everything is fine.
 */
@Composable
private fun StorageLocationRow(
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

    // مجوز نوشتن روی پوشه انتخابی هنوز معتبر است؟
    val hasGrant = customFolderUri?.let { uriText ->
        context.contentResolver.persistedUriPermissions.any {
            it.uri.toString() == uriText && it.isWritePermission
        }
    } == true

    val subtitle = when {
        customFolderUri == null -> "Downloads / Narcic Tube  ·  write access granted by the system picker"
        hasGrant -> folderDisplayName(customFolderUri) + "  ·  write access granted"
        else -> "Access revoked — re-grant to keep saving here"
    }

    SettingRow(
        title = "Storage Location",
        subtitle = subtitle,
        action = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (customFolderUri != null) {
                    TextButton(
                        onClick = {
                            runCatching {
                                context.contentResolver.releasePersistableUriPermission(
                                    Uri.parse(customFolderUri),
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                                )
                            }
                            onFolderPicked(null)
                        },
                    ) { Text("Default") }
                }
                OutlinedPill(
                    text = when {
                        customFolderUri == null -> "Change"
                        hasGrant -> "Change"
                        else -> "Re-grant"
                    },
                ) { picker.launch(null) }
            }
        },
    )
}

/** Human-readable folder name from a SAF tree URI ("primary:Music/Foo" → "Music/Foo"). */
private fun folderDisplayName(uriText: String): String =
    runCatching {
        val treeId = DocumentsContract.getTreeDocumentId(Uri.parse(uriText))
        treeId.substringAfter(':', treeId).ifBlank { "Selected folder" }
    }.getOrDefault("Selected folder")

/** Concurrent downloads: bounded slider; out-of-range values are impossible. */
@Composable
private fun ConcurrentDownloadsRow(
    value: Int,
    onSelect: (Int) -> Unit,
) {
    // Local drag value so DataStore is written once per gesture, not per tick.
    var dragging by remember(value) { mutableFloatStateOf(value.toFloat()) }

    SettingRow(
        title = "Concurrent Downloads",
        subtitle = "Max active bees in hive",
        action = {
            Text(
                text = value.toString(),
                style = MaterialTheme.typography.titleMedium,
                color = honeyAccentTextColor(),
                modifier = Modifier.padding(end = 10.dp),
            )
            Slider(
                value = dragging,
                onValueChange = { dragging = it },
                onValueChangeFinished = { onSelect(dragging.toInt()) },
                valueRange = AppSettings.MIN_CONCURRENT_DOWNLOADS.toFloat()..AppSettings.MAX_CONCURRENT_DOWNLOADS.toFloat(),
                steps = AppSettings.MAX_CONCURRENT_DOWNLOADS - AppSettings.MIN_CONCURRENT_DOWNLOADS - 1,
                modifier = Modifier.width(120.dp),
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                ),
            )
        },
    )
}

/**
 * Optional login session for yt-dlp. Some Instagram posts and age-restricted
 * or bot-checked YouTube videos can only be fetched by a logged-in browser;
 * importing that browser's cookies.txt (Netscape format) lets the downloader
 * do the same. The file stays in app-private storage and is never backed up.
 */
@Composable
private fun CookiesRow(epoch: Int, onShowLogin: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var hasCookies by remember(epoch) { mutableStateOf(YtDlpCookies.exists(context)) }
    var status by remember(epoch) { mutableStateOf<String?>(null) }

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

    Column {
        SettingRow(
            title = "YouTube & IG Cookies",
            subtitle = if (hasCookies) "cookies.txt imported" else "None imported",
            action = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedPill(text = "Log in") { onShowLogin() }
                    OutlinedPill(
                        text = if (hasCookies) "Remove" else "Import",
                    ) {
                        if (hasCookies) {
                            YtDlpCookies.clear(context)
                            hasCookies = false
                            status = "Cookies removed."
                        } else {
                            picker.launch(arrayOf("*/*"))
                        }
                    }
                }
            },
        )
        status?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, bottom = 12.dp),
            )
        }
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
