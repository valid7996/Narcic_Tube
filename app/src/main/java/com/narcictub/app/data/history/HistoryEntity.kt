package com.narcictub.app.data.history

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity backing download history. Storage-oriented; the domain layer
 * only ever sees HistoryItem (mapped in HistoryMappers).
 */
@Entity(tableName = "history")
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "source_url")
    val sourceUrl: String,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "file_name")
    val fileName: String? = null,

    @ColumnInfo(name = "mime_type")
    val mimeType: String? = null,

    @ColumnInfo(name = "quality")
    val quality: String? = null,

    /** Store enum names as TEXT; mapped back via HistoryMappers. */
    @ColumnInfo(name = "format")
    val format: String,

    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long = 0L,

    @ColumnInfo(name = "local_uri")
    val localUri: String? = null,

    @ColumnInfo(name = "status")
    val status: String,

    @ColumnInfo(name = "created_at_epoch_ms")
    val createdAtEpochMs: Long,

    @ColumnInfo(name = "completed_at_epoch_ms")
    val completedAtEpochMs: Long? = null,

    @ColumnInfo(name = "error_message")
    val errorMessage: String? = null,

    /** PHASE 22: real container duration (seconds), persisted at enqueue. */
    @ColumnInfo(name = "duration_seconds")
    val durationSeconds: Long? = null,
)
