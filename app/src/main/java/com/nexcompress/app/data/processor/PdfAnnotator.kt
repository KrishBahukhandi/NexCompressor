package com.nexcompress.app.data.processor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
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
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Flattens user markup (text boxes, pen strokes, highlighter) onto a PDF.
 *
 * Each annotated page is rendered upright at high resolution (so the page's own
 * /Rotate is handled by [PdfPageRenderer]), the annotations are drawn on top in
 * the same normalized coordinate space the editor used, and the result becomes a
 * crisp image page. Every page WITHOUT annotations is copied losslessly, so
 * untouched pages keep their selectable text and vectors.
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
                val pageAnnotations = byPage[i]
                if (pageAnnotations.isNullOrEmpty()) {
                    PdfFiles.importPagePreserving(out, src.getPage(i))
                } else {
                    flattenPage(out, renderer, src.getPage(i), i, pageAnnotations)
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
        } catch (e: Exception) {
            throw CompressionException("Couldn't save your changes to this PDF. It may be corrupted.")
        } finally {
            runCatching { out?.close() }
            runCatching { src?.close() }
            runCatching { renderer?.close() }
            temp.delete()
        }
    }

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
                    alpha = if (ann.highlighter) HIGHLIGHTER_ALPHA else 255
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
            if (ann.highlighter) alpha = HIGHLIGHTER_ALPHA
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
        val rotation = ((page.rotation % 360) + 360) % 360
        return if (rotation == 90 || rotation == 270) box.height to box.width
        else box.width to box.height
    }

    companion object {
        /** Render resolution of an annotated page (longest edge, px). */
        private const val RENDER_LONG_EDGE = 2200
        private const val JPEG_QUALITY = 0.9f
        private const val HIGHLIGHTER_ALPHA = 90
    }
}
