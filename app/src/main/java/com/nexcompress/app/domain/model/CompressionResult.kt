package com.nexcompress.app.domain.model

import kotlin.math.roundToInt

/** A single produced file plus its before/after footprint. */
data class OutputItem(
    val displayName: String,
    val originalSize: Long,
    val outputSize: Long,
    /** content:// URI string, ready for Share / View intents. */
    val uri: String,
    val type: FileType
)

/**
 * Aggregate outcome of a compression/conversion job. A PDF job yields one item;
 * a batch image job yields one item per converted picture. Screen 4 reads the
 * aggregate metrics; the History ledger persists each [OutputItem] as a row.
 */
data class CompressionResult(
    val items: List<OutputItem>
) {
    val originalSize: Long get() = items.sumOf { it.originalSize }
    val outputSize: Long get() = items.sumOf { it.outputSize }

    /** Absolute bytes reclaimed (never negative). This is the `savingsDelta`. */
    val savings: Long get() = (originalSize - outputSize).coerceAtLeast(0)

    /** Efficiency delta scaling, e.g. 72 -> "[ 72% Down ]". */
    val efficiencyPercent: Int
        get() = if (originalSize > 0) {
            ((savings.toDouble() / originalSize) * 100).roundToInt().coerceIn(0, 100)
        } else 0

    val primaryUri: String? get() = items.firstOrNull()?.uri
    val type: FileType get() = items.firstOrNull()?.type ?: FileType.PDF
    val isBatch: Boolean get() = items.size > 1
}
