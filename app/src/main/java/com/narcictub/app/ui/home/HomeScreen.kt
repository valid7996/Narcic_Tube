package com.narcictub.app.ui.home

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.narcictub.app.domain.model.MediaInfo
import com.narcictub.app.domain.model.MediaVariant
import com.narcictub.app.ui.theme.Hexagon
import com.narcictub.app.ui.theme.HoneyGradient
import com.narcictub.app.ui.theme.InkOnHoney
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.sp
import com.narcictub.app.ui.theme.NarcicTubTheme
import com.narcictub.app.ui.theme.honeySuccessColor
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

    // Notifications are requested the moment the user acts ("Download" tap)
    // — never at launch. A denial changes nothing: downloads still run and
    // in-app state remains the source of truth.
    val context = LocalContext.current
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    val requestNotificationsAndDownload = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        viewModel.onDownload()
    }

    HomeContent(
        state = state,
        onUrlChange = viewModel::onUrlChange,
        onClear = viewModel::onClear,
        onResolve = viewModel::onResolve,
        onDownload = requestNotificationsAndDownload,
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
    val successColor = honeySuccessColor()

    // لرزش فیلد هنگام لینک نامعتبر (سه نوسان کوتاه)
    val shakeX = remember { Animatable(0f) }
    LaunchedEffect(state.validationMessage) {
        if (state.validationMessage != null) {
            repeat(3) {
                shakeX.animateTo(10f, tween(60))
                shakeX.animateTo(-10f, tween(60))
            }
            shakeX.animateTo(0f, tween(60))
        }
    }

    LaunchedEffect(state.queuedSuccessfully) {
        if (state.queuedSuccessfully) {
            queuedVisible = true
            onQueuedMessageShown()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ─── Hero: مرکزچین — لوگوی شش‌ضلعی بزرگ + نام + شعار ───
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Hexagon(size = 96.dp, fill = HoneyGradient) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.Download,
                        contentDescription = null,
                        tint = InkOnHoney,
                        modifier = Modifier.size(44.dp),
                    )
                }
            }
            Text(
                text = "NarcicTub",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 14.dp),
            )
            Text(
                text = "Paste a link – pick a format – download.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        // ─── کارت لینک: هدر شش‌ضلعی + فیلد با Paste داخلی ───
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Hexagon(size = 10.dp, fill = HoneyGradient)
                    Text(
                        text = "Video or audio link",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer { translationX = shakeX.value }
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Link,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                    BasicTextField(
                        value = state.url,
                        onValueChange = onUrlChange,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 10.dp),
                        singleLine = true,
                        textStyle = TextStyle(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 14.sp,
                        ),
                        cursorBrush = Brush.verticalGradient(
                            listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primary),
                        ),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Go,
                        ),
                        keyboardActions = KeyboardActions(onGo = { onDownload() }),
                        decorationBox = { inner ->
                            Box(Modifier.fillMaxWidth()) {
                                if (state.url.isEmpty()) {
                                    Text(
                                        "https://youtube.com/watch?v=…",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                }
                                inner()
                            }
                        },
                    )
                    if (state.url.isNotEmpty()) {
                        IconButton(onClick = onClear, modifier = Modifier.size(28.dp)) {
                            Icon(
                                Icons.Filled.Clear,
                                contentDescription = "Clear",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            .clickable { clipboard.getText()?.text?.let(onUrlChange) }
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                    ) {
                        Text(
                            text = "Paste",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                state.validationMessage?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 6.dp, start = 2.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HoneyPillButton(
                        text = if (state.isResolving) "Resolving…" else "Resolve",
                        icon = Icons.Filled.Search,
                        modifier = Modifier.weight(1.35f),
                        enabled = state.isUrlValid && !state.isResolving,
                        onClick = onResolve,
                    )
                    HoneyPillButton(
                        text = if (state.isDownloading) "Queuing…" else "Download",
                        icon = Icons.Filled.Download,
                        modifier = Modifier.weight(1f),
                        // Gated on a genuine resolution — the hatch pattern
                        // marks the disabled state visually.
                        enabled = state.canDownload && !state.isDownloading,
                        onClick = onDownload,
                    )
                }
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
            // تأیید سبز «به کندو اضافه شد»
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(successColor.copy(alpha = 0.12f))
                    .border(1.dp, successColor.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = successColor,
                )
                Text(
                    text = "  Added to hive",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        state.errorMessage?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        // ─── کارت مراحل: شماره‌های شش‌ضلعی + توضیح دومتنی ───
        val step = when {
            state.resolvedMedia != null -> 3
            state.isUrlValid || state.url.isNotBlank() -> 2
            else -> 1
        }
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                listOf(
                    Triple(1, "Paste Media Link", "YouTube videos, shorts, IG reels, direct MP4 or MP3 links."),
                    Triple(2, "Resolve Formats", "Fetch quality tiers from 1080p to pure audio tracks."),
                    Triple(3, "Fly to Hive", "Live progress, speed gauges, and automatic archiving."),
                ).forEach { (n, title, description) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Hexagon(
                            size = 34.dp,
                            fill = if (step >= n) HoneyGradient else null,
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    text = n.toString(),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = if (step >= n) InkOnHoney else MaterialTheme.colorScheme.outline,
                                )
                            }
                        }
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = description,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (step >= n) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.outline
                                },
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))
    }
}

/** دکمه عسلی با گرادیان؛ غیرفعال = هاشور مورب ملایم (مطابق طراحی کندو) */
@Composable
private fun HoneyPillButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (enabled) {
                    Brush.linearGradient(listOf(Color(0xFFFFD25E), Color(0xFFEFA100)))
                } else {
                    Brush.linearGradient(listOf(colors.surfaceContainerHigh, colors.surfaceContainerHigh))
                },
            )
            .drawBehind {
                if (!enabled) {
                    val step = 9.dp.toPx()
                    var x = -size.height
                    while (x < size.width + size.height) {
                        drawLine(
                            colors.onSurfaceVariant.copy(alpha = 0.25f),
                            Offset(x, size.height),
                            Offset(x + size.height, 0f),
                            3f,
                        )
                        x += step
                    }
                }
            }
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (enabled) InkOnHoney else colors.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) InkOnHoney else colors.onSurfaceVariant,
            )
        }
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Real cover from the source when it provided one; a neat
                // placeholder while loading or when it didn't.
                info.thumbnailUrl?.let { thumb ->
                    MediaThumbnail(
                        url = thumb,
                        modifier = Modifier.padding(end = 12.dp),
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Ready to download",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    // Only real metadata: title when the source provided one,
                    // and the host — never a placeholder or a guess.
                    info.title?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = info.host,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
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
                        // Selectable format card — the primary flow control of
                        // the screen: tinted + outlined when selected, quiet
                        // surface otherwise.
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                                .selectable(
                                    selected = selected,
                                    role = Role.RadioButton,
                                    onClick = { onSelectVariant(variant.downloadUrl) },
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
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = if (variant.mimeType?.startsWith("audio/") == true) {
                                        Icons.Outlined.MusicNote
                                    } else {
                                        Icons.Filled.PlayCircle
                                    },
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = variantLabel(variant),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (selected) FontWeight.SemiBold else null,
                                    modifier = Modifier
                                        .padding(start = 12.dp)
                                        .weight(1f),
                                )
                                RadioButton(selected = selected, onClick = null)
                            }
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

/**
 * Small dependency-free cover image: decodes the source-provided thumbnail
 * (https only) off the main thread and shows a tidy placeholder while
 * loading or when the media has no usable cover. Purely decorative —
 * failures degrade to the placeholder, never an error.
 */
@Composable
private fun MediaThumbnail(url: String, modifier: Modifier = Modifier) {
    if (!url.startsWith("https://")) return
    var bitmap by remember(url) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(url) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                java.net.URL(url).openStream().use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
        }
    }
    Box(
        modifier = modifier
            .width(96.dp)
            .aspectRatio(16f / 10f)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        val loaded = bitmap
        if (loaded != null) {
            Image(
                bitmap = loaded.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = Icons.Filled.PlayCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
