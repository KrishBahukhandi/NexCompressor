package com.nexcompress.app.domain.model

/**
 * One page in the PDF page-editor's working set: a reference back to the source
 * page plus any extra clockwise rotation the user applied. Reordering this list
 * reorders the exported document; dropping an entry deletes that page.
 */
data class PdfPageOp(
    val sourceIndex: Int,
    /** Extra clockwise rotation in degrees, on top of the page's own: 0 / 90 / 180 / 270. */
    val rotation: Int = 0
) {
    /** A stable key for Compose lists (source page is unique within a document). */
    val key: Int get() = sourceIndex
}
