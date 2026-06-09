package com.nexcompress.app.domain.model

/**
 * A picked image paired with its user-editable output base name (no extension).
 * Used by the batch editor UI and consumed directly by the image converter.
 */
data class ImageBatchItem(
    val source: PickedFile,
    val outputName: String
)
