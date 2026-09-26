package com.narcictub.app.ui.status

import android.provider.DocumentsContract
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MusicNote
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.narcictub.app.ui.theme.honeySuccessColor
import java.text.DateFormat
import java.util.Date

/**
 * WHATSAPP STATUS SAVER — lists the photo/video statuses currently inside
 * the user-picked WhatsApp .Statuses folder (SAF tree, no storage
 * permission needed) and saves any of them into the gallery through the
 * REAL publish pipeline (MediaStore / custom location honored).
 *
 * The folder grant comes from Settings → WhatsApp Status. Statuses are
 * ephemeral by design — this screen only mirrors what the folder currently
 * holds, newest first.
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
}

@Composable
private fun StatusRow(
    status: StatusesViewModel.StatusItem,
    saved: Boolean,
    onSave: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
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
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                text = status.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                    .format(Date(status.lastModified)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
                    contentDescription = "Save to gallery",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
