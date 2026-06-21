package com.nexcompress.app.data.processor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.content.Context
import android.net.Uri
import android.text.StaticLayout
import android.text.TextPaint
import com.nexcompress.app.domain.model.AnnotationFont
import com.nexcompress.app.domain.model.CompressionException
import com.nexcompress.app.domain.model.ImageAnnotation
import com.nexcompress.app.domain.model.CompressionResult
import com.nexcompress.app.domain.model.FileType
import com.nexcompress.app.domain.model.InkAnnotation
import com.nexcompress.app.domain.model.OutputItem
import com.nexcompress.app.domain.model.PdfAnnotation
import com.nexcompress.app.domain.model.PickedFile
import com.nexcompress.app.domain.model.TextAnnotation
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.graphics.blend.BlendMode
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Flattens user markup (text boxes, pen strokes, highlighter, signatures) onto a PDF.
 *
 * The page's existing content is **never rasterized**: every annotated page is
 * imported losslessly and the markup is appended as a vector overlay in the
 * page's own coordinate space, so the document's real text stays selectable and
 * stays razor sharp at any zoom. Pen and highlighter strokes are true vector
 * paths; text notes and signatures ride along as small transparent stamps placed
 * exactly where the editor showed them. Pages without annotations are copied
 * untouched.
 *
 * Pages with a non-zero /Rotate fall back to the proven upright-raster path,
 * where [PdfPageRenderer] resolves the rotation for us — the overlay's simple
 * (un-rotated) coordinate mapping only has to be right for the overwhelmingly
 * common /Rotate 0 case.
 */
class PdfAnnotator(
    private val context: Context,
    private val storage: FileStorageManager
) {

    suspend fun annotate(
        source: PickedFile,
        annotations: List<PdfAnnotation>,
        outputBaseName: String
    ): CompressionResult = withContext(Dispatchers.IO) {
        if (annotations.isEmpty()) {
            throw CompressionException("Add at least one note, drawing or highlight first.")
        }
        val uri = Uri.parse(source.uriString)
        val temp = PdfFiles.copyToCache(context, uri, "annotate_")
        var renderer: PdfPageRenderer? = null
        var src: PDDocument? = null
        var out: PDDocument? = null
        try {
            val byPage = annotations.groupBy { it.pageIndex }
            renderer = PdfPageRenderer(context, uri)
            src = PDDocument.load(temp, MemoryUsageSetting.setupTempFileOnly())
            if (src.isEncrypted) {
                throw CompressionException(
                    "This PDF is password-protected. Unlock it first via Protect PDF, then mark it up."
                )
            }
            out = PDDocument()
            for (i in 0 until src.numberOfPages) {
                coroutineContext.ensureActive()
                val srcPage = src.getPage(i)
                val pageAnnotations = byPage[i]
                when {
                    pageAnnotations.isNullOrEmpty() ->
                        PdfFiles.importPagePreserving(out, srcPage)

                    normalizedRotation(srcPage) == 0 -> {
                        // Keep the real page (selectable text, sharp vectors) and
                        // just lay the markup on top.
                        val imported = PdfFiles.importPagePreserving(out, srcPage)
                        overlayPage(out, imported, pageAnnotations)
                    }

                    else ->
                        // Rotated page → raster path, which handles /Rotate correctly.
                        flattenPage(out, renderer, srcPage, i, pageAnnotations)
                }
            }

            val outName = storage.composeOutputName(outputBaseName, "pdf")
            val saved = storage.writeOutput(outName, "application/pdf") { os -> out.save(os) }
            CompressionResult(
                listOf(
                    OutputItem(
                        displayName = outName,
                        originalSize = source.sizeBytes,
                        outputSize = saved.sizeBytes,
                        uri = saved.uri.toString(),
                        type = FileType.PDF
                    )
                )
            )
        } catch (e: InvalidPasswordException) {
            throw CompressionException(
                "This PDF is password-protected. Unlock it first via Protect PDF, then mark it up."
            )
        } catch (oom: OutOfMemoryError) {
            throw CompressionException("This PDF is too large to mark up on this device.")
        } catch (e: CompressionException) {
            throw e
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            throw CompressionException("Couldn't save your changes to this PDF. It may be corrupted.")
        } finally {
            runCatching { out?.close() }
            runCatching { src?.close() }
            runCatching { renderer?.close() }
            temp.delete()
        }
    }

    // ------------------------------------------------------------------ //
    // Vector overlay (preserves the page; /Rotate 0)                      //
    // ------------------------------------------------------------------ //

    /**
     * Appends the page's markup as a vector overlay. The page content underneath
     * is left untouched, so its text stays selectable. Coordinates are normalized
     * (0..1) over the displayed page with the origin at the top-left, so they map
     * to user space as `x = llx + nx·w`, `y = lly + (1 − ny)·h`.
     */
    private fun overlayPage(out: PDDocument, page: PDPage, annotations: List<PdfAnnotation>) {
        val box = page.cropBox
        val llx = box.lowerLeftX
        val lly = box.lowerLeftY
        val w = box.width
        val h = box.height

        PDPageContentStream(
            out, page, PDPageContentStream.AppendMode.APPEND, /* compress = */ true,
            /* resetContext = */ true
        ).use { cs ->
            for (ann in annotations) {
                when (ann) {
                    is InkAnnotation -> overlayInk(cs, ann, llx, lly, w, h)
                    is TextAnnotation -> overlayText(out, cs, ann, llx, lly, w, h)
                    is ImageAnnotation -> overlayImage(out, cs, ann, llx, lly, w, h)
                }
            }
        }
    }

    private fun overlayInk(
        cs: PDPageContentStream,
        ann: InkAnnotation,
        llx: Float,
        lly: Float,
        w: Float,
        h: Float
    ) {
        val r = Color.red(ann.colorArgb) / 255f
        val g = Color.green(ann.colorArgb) / 255f
        val b = Color.blue(ann.colorArgb) / 255f
        val strokeW = (ann.widthFrac * w).coerceAtLeast(0.5f)
        fun px(n: Float) = llx + n * w
        fun py(n: Float) = lly + (1f - n) * h

        cs.saveGraphicsState()
        if (ann.highlighter) cs.setGraphicsStateParameters(highlighterState())

        if (ann.points.size == 1) {
            // A single tap → a filled dot so it still registers.
            cs.setNonStrokingColor(r, g, b)
            fillCircle(cs, px(ann.points[0].x), py(ann.points[0].y), strokeW / 2f)
            cs.restoreGraphicsState()
            return
        }

        cs.setStrokingColor(r, g, b)
        cs.setLineWidth(strokeW)
        cs.setLineCapStyle(if (ann.highlighter) 2 else 1) // square vs round
        cs.setLineJoinStyle(1) // round
        cs.moveTo(px(ann.points[0].x), py(ann.points[0].y))
        for (i in 1 until ann.points.size) {
            cs.lineTo(px(ann.points[i].x), py(ann.points[i].y))
        }
        cs.stroke()
        cs.restoreGraphicsState()
    }

    private fun overlayText(
        out: PDDocument,
        cs: PDPageContentStream,
        ann: TextAnnotation,
        llx: Float,
        lly: Float,
        w: Float,
        h: Float
    ) {
        if (ann.text.isBlank()) return
        // Render the note to a transparent stamp with the SAME StaticLayout the
        // editor used, so it's pixel-for-pixel what the user saw. Only this small
        // region is raster — the page's own text is left fully selectable.
        val base = when (ann.font) {
            AnnotationFont.SANS -> Typeface.SANS_SERIF
            AnnotationFont.SERIF -> Typeface.SERIF
            AnnotationFont.MONO -> Typeface.MONOSPACE
        }
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ann.colorArgb
            textSize = (ann.fontFrac * h * TEXT_STAMP_SCALE).coerceAtLeast(6f)
            typeface = Typeface.create(base, if (ann.bold) Typeface.BOLD else Typeface.NORMAL)
        }
        val wrapPx = (ann.maxWidthFrac * w * TEXT_STAMP_SCALE).toInt().coerceIn(1, MAX_STAMP_PX)
        val layout = StaticLayout.Builder
            .obtain(ann.text, 0, ann.text.length, paint, wrapPx)
            .setIncludePad(false)
            .build()
        val hPx = layout.height.coerceIn(1, MAX_STAMP_PX)

        val bmp = Bitmap.createBitmap(wrapPx, hPx, Bitmap.Config.ARGB_8888)
        try {
            layout.draw(Canvas(bmp))
            val image = LosslessFactory.createFromImage(out, bmp)
            val drawW = wrapPx / TEXT_STAMP_SCALE
            val drawH = hPx / TEXT_STAMP_SCALE
            val x = llx + ann.left * w
            val topY = lly + (1f - ann.top) * h
            cs.drawImage(image, x, topY - drawH, drawW, drawH)
        } finally {
            bmp.recycle()
        }
    }

    private fun overlayImage(
        out: PDDocument,
        cs: PDPageContentStream,
        ann: ImageAnnotation,
        llx: Float,
        lly: Float,
        w: Float,
        h: Float
    ) {
        val bmp = try {
            BitmapFactory.decodeByteArray(ann.png, 0, ann.png.size)
        } catch (e: Exception) {
            null
        } ?: return
        try {
            // PNG keeps its alpha through LosslessFactory's soft mask, so only the
            // signature ink lands on the page — never an opaque box around it.
            val image = LosslessFactory.createFromImage(out, bmp)
            val drawW = (ann.widthFrac * w).coerceAtLeast(1f)
            val drawH = drawW * bmp.height / bmp.width.coerceAtLeast(1)
            val x = llx + ann.left * w
            val topY = lly + (1f - ann.top) * h
            cs.drawImage(image, x, topY - drawH, drawW, drawH)
        } finally {
            bmp.recycle()
        }
    }

    /** A translucent, multiply-blended state so highlighter ink lets text show through. */
    private fun highlighterState(): PDExtendedGraphicsState = PDExtendedGraphicsState().apply {
        setStrokingAlphaConstant(HIGHLIGHTER_ALPHA)
        setNonStrokingAlphaConstant(HIGHLIGHTER_ALPHA)
        blendMode = BlendMode.MULTIPLY
    }

    /** Approximates a filled circle of radius [rad] with four cubic Béziers. */
    private fun fillCircle(cs: PDPageContentStream, cx: Float, cy: Float, rad: Float) {
        val k = 0.5523f * rad
        cs.moveTo(cx - rad, cy)
        cs.curveTo(cx - rad, cy + k, cx - k, cy + rad, cx, cy + rad)
        cs.curveTo(cx + k, cy + rad, cx + rad, cy + k, cx + rad, cy)
        cs.curveTo(cx + rad, cy - k, cx + k, cy - rad, cx, cy - rad)
        cs.curveTo(cx - k, cy - rad, cx - rad, cy - k, cx - rad, cy)
        cs.fill()
    }

    private fun normalizedRotation(page: PDPage): Int = ((page.rotation % 360) + 360) % 360

    // ------------------------------------------------------------------ //
    // Raster fallback — only for /Rotate'd pages                          //
    // ------------------------------------------------------------------ //

    private fun flattenPage(
        out: PDDocument,
        renderer: PdfPageRenderer,
        sourcePage: PDPage,
        index: Int,
        annotations: List<PdfAnnotation>
    ) {
        val bmp = renderer.renderPage(index, RENDER_LONG_EDGE)
            ?: throw CompressionException("Couldn't render page ${index + 1} to mark up.")
        try {
            drawAnnotations(bmp, annotations)
            val (vw, vh) = visualSizePoints(sourcePage)
            val page = PDPage(PDRectangle(vw, vh))
            out.addPage(page)
            val image = JPEGFactory.createFromImage(out, bmp, JPEG_QUALITY)
            PDPageContentStream(out, page).use { cs ->
                cs.drawImage(image, 0f, 0f, vw, vh)
            }
        } finally {
            bmp.recycle()
        }
    }

    /** Paints every annotation onto [bmp] using its normalized (upright) coords. */
    private fun drawAnnotations(bmp: Bitmap, annotations: List<PdfAnnotation>) {
        val canvas = Canvas(bmp)
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()
        for (ann in annotations) {
            when (ann) {
                is InkAnnotation -> drawInk(canvas, ann, w, h)
                is TextAnnotation -> drawText(canvas, ann, w, h)
                is ImageAnnotation -> drawImage(canvas, ann, w, h)
            }
        }
    }

    private fun drawInk(canvas: Canvas, ann: InkAnnotation, w: Float, h: Float) {
        if (ann.points.size < 2) {
            // A dot: draw a filled circle so single taps still register.
            if (ann.points.size == 1) {
                val p = ann.points[0]
                val r = (ann.widthFrac * w).coerceAtLeast(1f) / 2f
                val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = ann.colorArgb
                    alpha = if (ann.highlighter) HIGHLIGHTER_ALPHA_8BIT else 255
                }
                canvas.drawCircle(p.x * w, p.y * h, r, dot)
            }
            return
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ann.colorArgb
            style = Paint.Style.STROKE
            strokeWidth = (ann.widthFrac * w).coerceAtLeast(1f)
            strokeCap = if (ann.highlighter) Paint.Cap.SQUARE else Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            if (ann.highlighter) alpha = HIGHLIGHTER_ALPHA_8BIT
        }
        val path = Path()
        path.moveTo(ann.points[0].x * w, ann.points[0].y * h)
        for (i in 1 until ann.points.size) {
            path.lineTo(ann.points[i].x * w, ann.points[i].y * h)
        }
        canvas.drawPath(path, paint)
    }

    private fun drawText(canvas: Canvas, ann: TextAnnotation, w: Float, h: Float) {
        if (ann.text.isBlank()) return
        val base = when (ann.font) {
            AnnotationFont.SANS -> Typeface.SANS_SERIF
            AnnotationFont.SERIF -> Typeface.SERIF
            AnnotationFont.MONO -> Typeface.MONOSPACE
        }
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ann.colorArgb
            textSize = (ann.fontFrac * h).coerceAtLeast(6f)
            typeface = Typeface.create(base, if (ann.bold) Typeface.BOLD else Typeface.NORMAL)
        }
        // Wrap within maxWidthFrac of the page so paragraphs flow onto multiple
        // lines (StaticLayout also honors any explicit newlines the user typed).
        val wrapWidth = (ann.maxWidthFrac * w).toInt().coerceAtLeast(1)
        val layout = StaticLayout.Builder
            .obtain(ann.text, 0, ann.text.length, paint, wrapWidth)
            .setIncludePad(false)
            .build()
        canvas.save()
        canvas.translate(ann.left * w, ann.top * h)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun drawImage(canvas: Canvas, ann: ImageAnnotation, w: Float, h: Float) {
        val bmp = try {
            BitmapFactory.decodeByteArray(ann.png, 0, ann.png.size)
        } catch (e: Exception) {
            null
        } ?: return
        try {
            val drawW = (ann.widthFrac * w).coerceAtLeast(1f)
            val drawH = drawW * bmp.height / bmp.width.coerceAtLeast(1)
            val l = ann.left * w
            val t = ann.top * h
            canvas.drawBitmap(bmp, null, RectF(l, t, l + drawW, t + drawH), null)
        } finally {
            bmp.recycle()
        }
    }

    /** The page's visible size in points, accounting for its /Rotate. */
    private fun visualSizePoints(page: PDPage): Pair<Float, Float> {
        val box = page.cropBox
        val rotation = normalizedRotation(page)
        return if (rotation == 90 || rotation == 270) box.height to box.width
        else box.width to box.height
    }

    companion object {
        /** Render resolution of a rasterized (rotated) page (longest edge, px). */
        private const val RENDER_LONG_EDGE = 2200
        private const val JPEG_QUALITY = 0.9f

        /** Highlighter translucency, as a 0..1 alpha (vector) and 0..255 (raster). */
        private const val HIGHLIGHTER_ALPHA = 0.4f
        private const val HIGHLIGHTER_ALPHA_8BIT = 102

        /** Supersampling for text-note stamps so they stay crisp when zoomed. */
        private const val TEXT_STAMP_SCALE = 4f

        /** Pixel ceiling for a text stamp's width/height (OOM safeguard). */
        private const val MAX_STAMP_PX = 4096
    }
}
