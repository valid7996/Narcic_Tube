package com.narcictub.app.data.history

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * PHASE 22: version 2 adds the nullable duration_seconds column — the real
 * container duration extracted by the Phase 18 resolver now persists with
 * the history row (via enqueue) instead of being lost. The migration is a
 * trivial additive ALTER TABLE; all existing rows keep duration = null,
 * which renders honestly as "unknown" in the library.
 */
@Database(entities = [HistoryEntity::class], version = 2, exportSchema = true)
abstract class NarcicTubDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE history ADD COLUMN duration_seconds INTEGER")
            }
        }

        fun allMigrations(): Array<Migration> = arrayOf(MIGRATION_1_2)
    }
}
