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

    /**
     * Cumulative storage reclaimed ("Total Storage Reclaimed"). Each row is floored
     * at 0 via scalar max(0, …) so an entry that didn't shrink can't drag the total
     * negative — keeping it consistent with the per-row savings shown in the log.
     */
    @Query("SELECT COALESCE(SUM(max(0, originalSize - outputSize)), 0) FROM compression_history")
    fun observeTotalSavings(): Flow<Long>

    /** "Total Files Handled" counter. */
    @Query("SELECT COUNT(*) FROM compression_history")
    fun observeTotalCount(): Flow<Int>

    @Delete
    suspend fun delete(item: CompressionHistory)

    @Query("DELETE FROM compression_history WHERE fileId = :id")
    suspend fun deleteById(id: Long)
}
