package com.narcictub.app.ui.status

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.narcictub.app.ui.theme.honeySuccessColor
import java.text.DateFormat
import java.util.Date

/**
 * WHATSAPP STATUS SAVER — lists the photo/video statuses currently inside
 * the user-picked WhatsApp .Statuses folder with a decoded THUMBNAIL for
 * every row (so the user sees WHAT they are saving). Tapping a row opens
 * the full photo in an in-app viewer (videos open the system player);
 * saving publishes the file into Download/Narcic Tube/WhatsApp Status.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusesScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: StatusesViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val folderUri by viewModel.folderUri.collectAsStateWithLifecycle()
    val items by viewModel.items.collectAsStateWithLifecycle()
    val savedNames by viewModel.savedNames.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    /** آیتم در حال نمایش تمام‌صفحه. */
    var viewing by remember { mutableStateOf<StatusesViewModel.StatusItem?>(null) }

    LaunchedEffect(folderUri) {
        folderUri?.let { viewModel.refresh() }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("WhatsApp statuses") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            when {
                folderUri == null -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            "No statuses folder is set.",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "Pick it once in Settings, then come back.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
                items == null -> {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(padding),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                }
                items!!.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            "No statuses right now.",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "Open WhatsApp, view a few statuses, then come back here.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 16.dp, vertical = 8.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(items!!, key = { it.uri.toString() }) { status ->
                            StatusRow(
                                status = status,
                                saved = status.name in savedNames,
                                onClick = { viewing = status },
                                onSave = { viewModel.save(context, status) },
                            )
                        }
                    }
                }
            }
            message?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp),
                )
            }
        }
    }

    // نمایشگر تمام‌صفحه
    viewing?.let { status ->
        StatusViewerDialog(
            status = status,
            viewModel = viewModel,
            onDismiss = { viewing = null },
        )
    }
}

@Composable
private fun StatusRow(
    status: StatusesViewModel.StatusItem,
    saved: Boolean,
    onClick: () -> Unit,
    onSave: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable { onClick() } // لمس ردیف = دیدن کامل
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusThumbnail(status = status)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                text = status.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = when {
                        status.mime.startsWith("video/") -> Icons.Filled.Videocam
                        status.mime.startsWith("image/") -> Icons.Filled.Image
                        else -> Icons.Filled.MusicNote
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = "  " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                        .format(Date(status.lastModified)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (saved) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = "Saved",
                tint = honeySuccessColor(),
            )
        } else {
            IconButton(onClick = onSave) {
                Icon(
                    Icons.Filled.Download,
                    contentDescription = "Save",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** بندانگشتی واقعی از فایل؛ نبود آن → آیکون نوع روی پس‌زمینه عسلی. */
@Composable
private fun StatusThumbnail(status: StatusesViewModel.StatusItem) {
    Box(
        modifier = Modifier
            .size(58.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        val thumb = status.thumbnail
        if (thumb != null) {
            Image(
                bitmap = thumb.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (status.mime.startsWith("video/")) {
                // نشان پخش روی ویدیوها
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.25f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                    )
                }
            }
        } else {
            Icon(
                imageVector = when {
                    status.mime.startsWith("video/") -> Icons.Filled.Videocam
                    status.mime.startsWith("image/") -> Icons.Filled.Image
                    else -> Icons.Filled.MusicNote
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * نمایشگر تمام‌صفحه: عکس داخل برنامه (دیکد کامل تا ۲۰۴۸px)؛ ویدیو با
 * پخش‌کننده سیستم (ACTION_VIEW + grant خواندن همان URI).
 */
@Composable
private fun StatusViewerDialog(
    status: StatusesViewModel.StatusItem,
    viewModel: StatusesViewModel,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    if (status.mime.startsWith("image/")) {
        var bitmap by remember(status.uri) { mutableStateOf<Bitmap?>(null) }
        LaunchedEffect(status.uri) {
            bitmap = viewModel.decodeFullImage(status.uri)
        }
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.92f))
                    .clickable { onDismiss() },
                contentAlignment = Alignment.Center,
            ) {
                val loaded = bitmap
                if (loaded != null) {
                    Image(
                        bitmap = loaded.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
                Text(
                    text = "tap to close",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 24.dp),
                )
            }
        }
    } else {
        // ویدیو: پخش‌کننده سیستم با دسترسی خواندن همان فایل
        LaunchedEffect(status.uri) {
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(status.uri, status.mime)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                )
            }
            onDismiss()
        }
    }
}
