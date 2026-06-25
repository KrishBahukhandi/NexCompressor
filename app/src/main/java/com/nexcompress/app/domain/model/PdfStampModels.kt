package com.nexcompress.app.domain.model

/**
 * A text watermark stamped across every page of a PDF — centered, translucent,
 * and (by default) on a 45° diagonal, the way the major converters render it.
 */
data class WatermarkSpec(
    val text: String,
    /** Mark opacity, 0..1. */
    val opacity: Float = 0.22f,
    val diagonal: Boolean = true,
    val colorArgb: Int = 0xFF9E9E9E.toInt(),
    /** When true, the mark is repeated as a mosaic across the whole page. */
    val tiled: Boolean = false
)

/** How a stamped page number reads. */
enum class PageNumberFormat { NUMBER_ONLY, PAGE_OF_TOTAL, PAGE_PREFIX }

/** Where a small stamp (page number) sits on the page. */
enum class StampPosition { TOP_LEFT, TOP_CENTER, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT }

/** Page-numbering options applied to every page. */
data class PageNumberSpec(
    val format: PageNumberFormat = PageNumberFormat.PAGE_OF_TOTAL,
    val position: StampPosition = StampPosition.BOTTOM_CENTER,
    /** The number printed on the first numbered page (later pages count up). */
    val startNumber: Int = 1,
    /** Leave the first page unnumbered (e.g. a cover/title page). */
    val skipFirstPage: Boolean = false
)
