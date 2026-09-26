package com.narcictub.app.ui.status

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.narcictub.app.data.local.MediaStoreFileWriter
import com.narcictub.app.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * WhatsApp status saver logic: reads the child documents of the picked
 * .Statuses tree (photos/videos, newest first) via the SAF contract, and
 * saves items through the REAL [MediaStoreFileWriter] publish pipeline —
 * honoring the user's storage location settings. Nothing is simulated.
 */
@javax.inject.Singleton
class StatusesViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    settingsRepository: SettingsRepository,
    private val mediaStoreWriter: MediaStoreFileWriter,
) : ViewModel() {

    data class StatusItem(
        val name: String,
        val mime: String,
        val lastModified: Long,
        val uri: Uri,
    )

    val folderUri: StateFlow<String?> = settingsRepository.settings
        .map { it.whatsappStatusFolderUri }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** null = still loading; empty = folder picked but currently empty. */
    private val _items = MutableStateFlow<List<StatusItem>?>(null)
    val items: StateFlow<List<StatusItem>?> = _items.asStateFlow()

    private val _savedNames = MutableStateFlow<Set<String>>(emptySet())
    val savedNames: StateFlow<Set<String>> = _savedNames.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun refresh() {
        val treeText = folderUri.value ?: return
        viewModelScope.launch {
            _items.value = withContext(Dispatchers.IO) { queryStatuses(treeText) }
        }
    }

    private fun queryStatuses(treeText: String): List<StatusItem> {
        return runCatching {
            val treeUri = Uri.parse(treeText)
            val resolver = appContext.contentResolver
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri),
            )
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            )
            val result = mutableListOf<StatusItem>()
            resolver.query(children, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val mime = cursor.getString(2) ?: continue
                    if (!(mime.startsWith("image/") || mime.startsWith("video/"))) continue
                    val docId = cursor.getString(0) ?: continue
                    result += StatusItem(
                        name = cursor.getString(1) ?: docId,
                        mime = mime,
                        lastModified = cursor.getLong(3),
                        uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId),
                    )
                }
            }
            result.sortedByDescending { it.lastModified }
        }.getOrNull().orEmpty()
    }

    fun save(context: Context, item: StatusItem) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val resolver: ContentResolver = context.contentResolver
                    val staging = File(
                        context.cacheDir,
                        "status_${System.nanoTime()}_${item.name.substringAfterLast('/')}",
                    )
                    resolver.openInputStream(item.uri)?.use { input ->
                        staging.outputStream().use { output -> input.copyTo(output) }
                    } ?: error("status stream unavailable")
                    val published = mediaStoreWriter.publish(
                        stagingFile = staging,
                        displayName = item.name,
                        mimeType = item.mime,
                    )
                    staging.delete()
                    published
                }.isSuccess
            }
            if (ok) {
                _savedNames.value = _savedNames.value + item.name
                _message.value = "Saved to your gallery."
            } else {
                _message.value = "Couldn't save this status."
            }
            kotlinx.coroutines.delay(3000)
            _message.value = null
        }
    }
}
