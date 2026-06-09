package com.nexcompress.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/** Single-table SQLite database backing the local activity log. */
@Database(
    entities = [CompressionHistory::class],
    version = 1,
    exportSchema = false
)
abstract class NexCompressDatabase : RoomDatabase() {

    abstract fun historyDao(): CompressionHistoryDao

    companion object {
        private const val DB_NAME = "nexcompress.db"

        @Volatile
        private var INSTANCE: NexCompressDatabase? = null

        fun getInstance(context: Context): NexCompressDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    NexCompressDatabase::class.java,
                    DB_NAME
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
