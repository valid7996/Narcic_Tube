package com.narcictub.app.ui.status

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.util.Size
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.narcictub.app.data.local.MediaStoreFileWriter
import dagger.hilt.android.lifecycle.HiltViewModel
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
 * .Statuses tree (photos/videos, newest first) via the SAF contract, decodes
 * a small thumbnail for each (so the list is recognizable at a glance), and
 * saves items through the REAL [MediaStoreFileWriter] publish pipeline into
 * the app's download folder under a dedicated "WhatsApp Status" subfolder.
 * Nothing is simulated.
 */
@HiltViewModel
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
        /** Small decoded preview; null → the row falls back to a type icon. */
        val thumbnail: Bitmap?,
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
                    val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    result += StatusItem(
                        name = cursor.getString(1) ?: docId,
                        mime = mime,
                        lastModified = cursor.getLong(3),
                        uri = uri,
                        thumbnail = decodeThumbnail(resolver, uri, mime),
                    )
                }
            }
            result.sortedByDescending { it.lastModified }
        }.getOrNull().orEmpty()
    }

    /** بندانگشتی کوچک: ویدیو از loadThumbnail (API 29+)، عکس با نمونه‌گیری. */
    private fun decodeThumbnail(resolver: ContentResolver, uri: Uri, mime: String): Bitmap? {
        if (mime.startsWith("video/")) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
            return runCatching {
                resolver.loadThumbnail(uri, Size(256, 256), null)
            }.getOrNull()
        }
        return decodeSampled(resolver, uri, maxDim = 256)
    }

    /** عکس با inSampleSize تا حداکثر maxDim پیکسل — برای بندانگشتی و نمایش کامل. */
    private fun decodeSampled(resolver: ContentResolver, uri: Uri, maxDim: Int): Bitmap? =
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxDim || bounds.outHeight / (sample * 2) >= maxDim) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        }.getOrNull()

    /** نمایش کامل عکس (نمونه‌گیری تا ۲۰۴۸px — روی گوشی‌های معمولی بی‌خطر). */
    suspend fun decodeFullImage(uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        decodeSampled(appContext.contentResolver, uri, maxDim = 2048)
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
                    // ذخیره در پوشه برنامه: Download/Narcic Tube/WhatsApp Status
                    val published = mediaStoreWriter.publish(
                        stagingFile = staging,
                        displayName = item.name,
                        mimeType = item.mime,
                        subDirectory = WA_SUBDIRECTORY,
                    )
                    staging.delete()
                    published
                }.isSuccess
            }
            if (ok) {
                _savedNames.value = _savedNames.value + item.name
                _message.value = "Saved to $WA_SUBDIRECTORY"
            } else {
                _message.value = "Couldn't save this status."
            }
            kotlinx.coroutines.delay(3000)
            _message.value = null
        }
    }

    companion object {
        /** زیرپوشه اختصاصی استوری‌ها داخل پوشه دانلود برنامه. */
        const val WA_SUBDIRECTORY = "Download/Narcic Tube/WhatsApp Status"
    }
}
