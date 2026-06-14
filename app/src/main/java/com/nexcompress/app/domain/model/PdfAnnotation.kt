package com.nexcompress.app.domain.model

/** A point normalized (0..1) over the displayed (upright) page, origin top-left. */
data class NormPoint(val x: Float, val y: Float)

/**
 * A markup item the user places on a PDF page. Coordinates are normalized over
 * the *displayed* (upright) page so they map cleanly onto the rendered page
 * regardless of its /Rotate — see [com.nexcompress.app.data.processor.PdfAnnotator].
 */
sealed interface PdfAnnotation {
    val pageIndex: Int
}

/** A typed text box anchored at its top-left. */
data class TextAnnotation(
    override val pageIndex: Int,
    val text: String,
    val left: Float,
    val top: Float,
    /** Text size as a fraction of page height (e.g. 0.03 ≈ 3% of page height). */
    val fontFrac: Float,
    val colorArgb: Int
) : PdfAnnotation

/** A freehand stroke (pen) or translucent highlighter swipe. */
data class InkAnnotation(
    override val pageIndex: Int,
    val points: List<NormPoint>,
    /** Stroke width as a fraction of page width. */
    val widthFrac: Float,
    val colorArgb: Int,
    val highlighter: Boolean
) : PdfAnnotation
