package com.nexcompress.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.nexcompress.app.domain.model.FileType

/**
 * Room entity for the Local Caching Ledger. One row per produced file.
 * Room persists [type] automatically using the enum constant name.
 */
@Entity(tableName = "compression_history")
data class CompressionHistory(
    @PrimaryKey(autoGenerate = true) val fileId: Long = 0,
    val originalName: String,
    val originalSize: Long,
    val outputSize: Long,
    val type: FileType,
    /** content:// URI string for the saved output, used by Share/View intents. */
    val outputUri: String,
    val timestamp: Long = System.currentTimeMillis()
)

/** Bytes reclaimed for this entry (kept out of the entity so Room maps only columns). */
val CompressionHistory.savings: Long
    get() = (originalSize - outputSize).coerceAtLeast(0)
