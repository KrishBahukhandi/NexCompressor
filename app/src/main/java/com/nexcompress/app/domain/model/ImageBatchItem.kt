package com.nexcompress.app.domain.model

/**
 * A picked image paired with its user-editable output base name (no extension)
 * and an optional per-image edit (rotate / flip / crop / resize). The edit's
 * geometry is applied by the batch converters; its format/quality fields are
 * ignored there because those are chosen once for the whole batch.
 */
data class ImageBatchItem(
    val source: PickedFile,
    val outputName: String,
    val editSpec: ImageEditSpec? = null
) {
    /** True when this image has a non-identity edit (so the UI can badge it). */
    val isEdited: Boolean
        get() = editSpec != null && with(editSpec) {
            rotationDegrees % 360 != 0 || flipHorizontal || flipVertical ||
                !crop.isFull || maxLongEdge != null
        }
}
