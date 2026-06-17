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

/** Font family choices for text boxes — all built-in, so no fonts are bundled. */
enum class AnnotationFont { SANS, SERIF, MONO }

/** A typed text box anchored at its top-left; wraps within [maxWidthFrac]. */
data class TextAnnotation(
    override val pageIndex: Int,
    val text: String,
    val left: Float,
    val top: Float,
    /** Text size as a fraction of page height (e.g. 0.03 ≈ 3% of page height). */
    val fontFrac: Float,
    val colorArgb: Int,
    val font: AnnotationFont = AnnotationFont.SANS,
    val bold: Boolean = false,
    /** Max line width as a fraction of page width before the text wraps (paragraphs). */
    val maxWidthFrac: Float = 0.8f
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

/**
 * A placed image (e.g. a drawn signature) as a transparent PNG. Anchored at
 * top-left with [widthFrac] of page width; its height is derived from the
 * image's own aspect at render time so it never distorts.
 */
data class ImageAnnotation(
    override val pageIndex: Int,
    val png: ByteArray,
    val left: Float,
    val top: Float,
    val widthFrac: Float
) : PdfAnnotation {
    // ByteArray breaks data-class equality; identity is enough for our use.
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}
