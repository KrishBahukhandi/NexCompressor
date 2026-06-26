package com.nexcompress.app.data.repository

import com.nexcompress.app.data.local.CompressionHistory
import com.nexcompress.app.data.local.CompressionHistoryDao
import com.nexcompress.app.data.processor.FileStorageManager
import com.nexcompress.app.domain.model.CompressionResult
import kotlinx.coroutines.flow.Flow

/**
 * Thin domain-facing wrapper over the Room DAO. Exposes reactive ledger streams
 * and translates a finished [CompressionResult] into persisted history rows.
 */
class HistoryRepository(
    private val dao: CompressionHistoryDao,
    private val storage: FileStorageManager
) {

    val history: Flow<List<CompressionHistory>> = dao.observeAll()

    /** Local storage write routine invoked once processing completes. */
    suspend fun record(result: CompressionResult) {
        val now = System.currentTimeMillis()
        val rows = result.items.mapIndexed { index, item ->
            CompressionHistory(
                originalName = item.displayName,
                originalSize = item.originalSize,
                outputSize = item.outputSize,
                type = item.type,
                outputUri = item.uri,
                // Offset within a batch preserves insertion order in the ledger.
                timestamp = now + index
            )
        }
        dao.insertAll(rows)
    }

    suspend fun delete(item: CompressionHistory) = dao.delete(item)

    /**
     * Renames a saved output file on disk and updates its ledger row.
     * Returns true on success, false if the file rename couldn't be applied.
     */
    suspend fun rename(item: CompressionHistory, newBaseName: String): Boolean {
        val renamed = storage.renameOutput(item.outputUri, item.originalName, newBaseName)
            ?: return false
        dao.update(item.copy(originalName = renamed.displayName, outputUri = renamed.uriString))
        return true
    }
}
