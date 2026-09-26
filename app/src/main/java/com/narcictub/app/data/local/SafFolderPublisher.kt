package com.narcictub.app.data.local

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.IOException

/**
 * Publishes a finished download into a USER-PICKED SAF document tree (the
 * OpenDocumentTree result persisted by the settings screen). Uses only the
 * API 19+ DocumentsContract surface — no Q-only symbols — so the class is
 * safe to load on every supported API level, pre-Q included.
 *
 * Publication protocol mirrors [QPlusMediaStorePublisher]:
 *  1. create the target document inside the picked tree
 *  2. stream + flush the staging file into it
 *  3. on ANY failure delete the created document again — no partial file
 *     is ever left in the user's folder
 *
 * Access relies on the persistable URI grant the settings screen took when
 * the folder was picked; a revoked grant surfaces as SecurityException and
 * the caller falls back to the platform storage path.
 */
internal object SafFolderPublisher {

    fun publish(
        resolver: ContentResolver,
        treeUri: Uri,
        stagingFile: File,
        safeName: String,
        mimeType: String?,
    ): Uri {
        val parentDocumentUri = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        val documentUri = DocumentsContract.createDocument(
            resolver,
            parentDocumentUri,
            mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream",
            safeName,
        ) ?: throw IOException("SAF document create failed")

        try {
            resolver.openOutputStream(documentUri)?.use { output ->
                stagingFile.inputStream().use { input ->
                    input.copyTo(output, COPY_BUFFER_BYTES)
                }
                output.flush()
            } ?: throw IOException("SAF output stream unavailable")
            return documentUri
        } catch (e: Exception) {
            // Never leave a dangling/partial document in the user's folder.
            runCatching { DocumentsContract.deleteDocument(resolver, documentUri) }
            throw e
        }
    }

    private const val COPY_BUFFER_BYTES = 16 * 1024
}
