package com.nexcompress.app.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Inserts + reactive query flows for the compression ledger. */
@Dao
interface CompressionHistoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: CompressionHistory): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<CompressionHistory>)

    @Update
    suspend fun update(item: CompressionHistory)

    @Query("SELECT * FROM compression_history ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<CompressionHistory>>

    @Delete
    suspend fun delete(item: CompressionHistory)

    @Query("DELETE FROM compression_history WHERE fileId = :id")
    suspend fun deleteById(id: Long)
}
