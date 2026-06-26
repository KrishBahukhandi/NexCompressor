package com.nexcompress.app.data.processor

import android.content.Context
import android.graphics.Color
import android.net.Uri
import com.nexcompress.app.domain.model.CompressionException
import com.nexcompress.app.domain.model.CompressionResult
import com.nexcompress.app.domain.model.FileType
import com.nexcompress.app.domain.model.OutputItem
import com.nexcompress.app.domain.model.PickedFile
import com.nexcompress.app.domain.model.WatermarkSpec
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.coroutineContext
import kotlin.math.hypot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Stamps a translucent text watermark across every page. The page content is
 * preserved (real text and vectors stay intact and selectable), and the mark is
 * appended as a vector overlay — centered, sized to span the page, and optionally
 * on a 45° diagonal, the way the major converters render it.
 */
class PdfWatermarker(
    private val context: Context,
    private val storage: FileStorageManager
) {
    suspend fun apply(
        source: PickedFile,
        spec: WatermarkSpec,
        outputBaseName: String
    ): CompressionResult = withContext(Dispatchers.IO) {
        val text = PdfText.asciiSafe(spec.text).trim()
        if (text.isBlank()) throw CompressionException("Enter watermark text first.")

        val temp = PdfFiles.copyToCache(context, Uri.parse(source.uriString), "watermark_")
        val rebuilt = File.createTempFile("nexwm_", ".pdf", context.cacheDir)
        var doc: PDDocument? = null
        try {
            doc = PDDocument.load(temp, MemoryUsageSetting.setupTempFileOnly())
            if (doc.isEncrypted) {
                throw CompressionException(
                    "This PDF is password-protected. Unlock it first via Protect PDF."
                )
            }
            if (doc.numberOfPages <= 0) throw CompressionException("This PDF has no pages.")

            val opacity = spec.opacity.coerceIn(0.05f, 1f)
            for (page in doc.pages) {
                coroutineContext.ensureActive()
                stamp(doc, page, text, spec, opacity)
            }

            FileOutputStream(rebuilt).use { doc.save(it) }
            val outName = storage.composeOutputName(outputBaseName, "pdf")
            val saved = storage.writeOutput(outName, "application/pdf") { os ->
                rebuilt.inputStream().use { it.copyTo(os) }
            }
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
            throw CompressionException("This PDF is password-protected. Unlock it first via Protect PDF.")
        } catch (oom: OutOfMemoryError) {
            throw CompressionException("This PDF is too large to watermark on this device.")
        } catch (e: CompressionException) {
            throw e
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            throw CompressionException("Couldn't add the watermark. The PDF may be corrupted.")
        } finally {
            runCatching { doc?.close() }
            rebuilt.delete()
            temp.delete()
        }
    }

    private fun stamp(doc: PDDocument, page: PDPage, text: String, spec: WatermarkSpec, opacity: Float) {
        val box = page.cropBox
        val font = PDType1Font.HELVETICA_BOLD
        val angle = if (spec.diagonal) Math.toRadians(45.0) else 0.0

        PDPageContentStream(
            doc, page, PDPageContentStream.AppendMode.APPEND, true, true
        ).use { cs ->
            cs.saveGraphicsState()
            cs.setGraphicsStateParameters(
                PDExtendedGraphicsState().apply { setNonStrokingAlphaConstant(opacity) }
            )
            cs.setNonStrokingColor(
                Color.red(spec.colorArgb) / 255f,
                Color.green(spec.colorArgb) / 255f,
                Color.blue(spec.colorArgb) / 255f
            )
            if (spec.tiled) drawTiled(cs, font, text, box, angle)
            else drawCentered(cs, font, text, box, angle)
            cs.restoreGraphicsState()
        }
    }

    /** One large mark, sized to span the page, centered. */
    private fun drawCentered(
        cs: PDPageContentStream,
        font: PDType1Font,
        text: String,
        box: com.tom_roush.pdfbox.pdmodel.common.PDRectangle,
        angle: Double
    ) {
        val w = box.width
        val h = box.height
        val cx = box.lowerLeftX + w / 2f
        val cy = box.lowerLeftY + h / 2f
        val unitWidth = (font.getStringWidth(text) / 1000f).coerceAtLeast(0.001f)
        val target = if (angle != 0.0) 0.82f * hypot(w.toDouble(), h.toDouble()).toFloat() else 0.82f * w
        val fontSize = (target / unitWidth).coerceIn(8f, 512f)
        val textWidth = unitWidth * fontSize
        cs.beginText()
        cs.setFont(font, fontSize)
        cs.setTextMatrix(Matrix.getRotateInstance(angle, cx, cy))
        // Center the (rotated) text: shift back half its width and a third cap-height.
        cs.newLineAtOffset(-textWidth / 2f, -fontSize * 0.35f)
        cs.showText(text)
        cs.endText()
    }

    /** A repeated "mosaic" of small marks tiling the whole page (brick-offset rows). */
    private fun drawTiled(
        cs: PDPageContentStream,
        font: PDType1Font,
        text: String,
        box: com.tom_roush.pdfbox.pdmodel.common.PDRectangle,
        angle: Double
    ) {
        val w = box.width
        val h = box.height
        val llx = box.lowerLeftX
        val lly = box.lowerLeftY
        val fontSize = (w * 0.09f).coerceIn(10f, 48f)
        val unitWidth = (font.getStringWidth(text) / 1000f).coerceAtLeast(0.001f)
        val textWidth = unitWidth * fontSize
        val stepX = textWidth + fontSize * 2.0f
        val stepY = fontSize * 3.2f
        var rowIndex = 0
        var y = lly - stepY
        while (y < lly + h + stepY) {
            val rowOffset = if (rowIndex % 2 == 0) 0f else stepX / 2f
            var x = llx - stepX + rowOffset
            while (x < llx + w + stepX) {
                cs.beginText()
                cs.setFont(font, fontSize)
                cs.setTextMatrix(Matrix.getRotateInstance(angle, x, y))
                cs.showText(text)
                cs.endText()
                x += stepX
            }
            y += stepY
            rowIndex++
        }
    }
}
