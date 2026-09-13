package com.narcictub.app.data.history

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: HistoryEntity): Long

    @Query("SELECT * FROM history ORDER BY created_at_epoch_ms DESC, id DESC")
    fun observeAll(): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM history WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): HistoryEntity?

    @Query(
        "UPDATE history SET status = :status, error_message = :errorMessage, " +
            "completed_at_epoch_ms = :completedAtEpochMs WHERE id = :id",
    )
    suspend fun updateStatus(
        id: Long,
        status: String,
        errorMessage: String?,
        completedAtEpochMs: Long?,
    )

    @Delete
    suspend fun delete(entity: HistoryEntity)

    @Query("DELETE FROM history WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM history")
    suspend fun clear()

    @Query("UPDATE history SET local_uri = :localUri WHERE id = :id")
    suspend fun updateLocalUri(id: Long, localUri: String)

    @Query("UPDATE history SET size_bytes = :sizeBytes WHERE id = :id")
    suspend fun updateSizeBytes(id: Long, sizeBytes: Long)
}
