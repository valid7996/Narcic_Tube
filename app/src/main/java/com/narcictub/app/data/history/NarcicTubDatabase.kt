package com.narcictub.app.data.history

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [HistoryEntity::class], version = 1, exportSchema = true)
abstract class NarcicTubDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao
}
