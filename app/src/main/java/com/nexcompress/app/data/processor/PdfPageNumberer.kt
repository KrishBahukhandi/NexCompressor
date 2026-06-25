package com.nexcompress.app.data.processor

import android.content.Context
import android.net.Uri
import com.nexcompress.app.domain.model.CompressionException
import com.nexcompress.app.domain.model.CompressionResult
import com.nexcompress.app.domain.model.FileType
import com.nexcompress.app.domain.model.OutputItem
import com.nexcompress.app.domain.model.PageNumberFormat
import com.nexcompress.app.domain.model.PageNumberSpec
import com.nexcompress.app.domain.model.PickedFile
import com.nexcompress.app.domain.model.StampPosition
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Stamps page numbers ("1", "1 of N", or "Page 1") onto every page in the chosen
 * corner. The page content is preserved and the number is a small text overlay.
 * Numbering follows the page order in the document.
 */
class PdfPageNumberer(
    private val context: Context,
    private val storage: FileStorageManager
) {
    suspend fun apply(
        source: PickedFile,
        spec: PageNumberSpec,
        outputBaseName: String
    ): CompressionResult = withContext(Dispatchers.IO) {
        val temp = PdfFiles.copyToCache(context, Uri.parse(source.uriString), "pagenum_")
        val rebuilt = File.createTempFile("nexpn_", ".pdf", context.cacheDir)
        var doc: PDDocument? = null
        try {
            doc = PDDocument.load(temp, MemoryUsageSetting.setupTempFileOnly())
            if (doc.isEncrypted) {
                throw CompressionException(
                    "This PDF is password-protected. Unlock it first via Protect PDF."
                )
            }
            val total = doc.numberOfPages
            if (total <= 0) throw CompressionException("This PDF has no pages.")
            val start = spec.startNumber.coerceAtLeast(0)
            // Optionally leave the cover page unnumbered; "of N" counts only the
            // pages that actually get a number.
            val firstNumbered = if (spec.skipFirstPage) 1 else 0
            val numberedCount = (total - firstNumbered).coerceAtLeast(0)
            val last = start + numberedCount - 1

            doc.pages.forEachIndexed { index, page ->
                coroutineContext.ensureActive()
                if (index < firstNumbered) return@forEachIndexed
                val displayNum = start + (index - firstNumbered)
                stamp(doc, page, labelFor(displayNum, last, spec.format), spec.position)
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
            throw CompressionException("This PDF is too large to number on this device.")
        } catch (e: CompressionException) {
            throw e
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            throw CompressionException("Couldn't add page numbers. The PDF may be corrupted.")
        } finally {
            runCatching { doc?.close() }
            rebuilt.delete()
            temp.delete()
        }
    }

    private fun labelFor(num: Int, last: Int, format: PageNumberFormat): String = when (format) {
        PageNumberFormat.NUMBER_ONLY -> "$num"
        PageNumberFormat.PAGE_OF_TOTAL -> "$num of $last"
        PageNumberFormat.PAGE_PREFIX -> "Page $num"
    }

    private fun stamp(doc: PDDocument, page: PDPage, label: String, position: StampPosition) {
        val box = page.cropBox
        val rotation = ((page.rotation % 360) + 360) % 360
        // Work in the page's *visual* (displayed, upright) space so the number
        // lands in the right corner and reads horizontally even on /Rotate'd pages.
        val visualW = if (rotation == 90 || rotation == 270) box.height else box.width
        val visualH = if (rotation == 90 || rotation == 270) box.width else box.height

        val font = PDType1Font.HELVETICA
        val size = (visualH * 0.014f).coerceIn(9f, 14f)
        val textWidth = font.getStringWidth(label) / 1000f * size
        val x = when (position) {
            StampPosition.TOP_LEFT, StampPosition.BOTTOM_LEFT -> MARGIN
            StampPosition.TOP_CENTER, StampPosition.BOTTOM_CENTER -> (visualW - textWidth) / 2f
            StampPosition.TOP_RIGHT, StampPosition.BOTTOM_RIGHT -> visualW - MARGIN - textWidth
        }
        val baseline = when (position) {
            StampPosition.TOP_LEFT, StampPosition.TOP_CENTER, StampPosition.TOP_RIGHT ->
                visualH - MARGIN - size
            else -> MARGIN
        }
        PDPageContentStream(
            doc, page, PDPageContentStream.AppendMode.APPEND, true, true
        ).use { cs ->
            applyVisualSpace(cs, page, rotation)
            cs.beginText()
            cs.setFont(font, size)
            cs.setNonStrokingColor(0.2f, 0.2f, 0.2f)
            cs.newLineAtOffset(x, baseline)
            cs.showText(label)
            cs.endText()
        }
    }

    /**
     * Concatenates a transform so subsequent drawing uses the page's *displayed*
     * coordinate system: origin at the visual bottom-left, x→right, y→up, upright.
     * (PDF /Rotate turns the page clockwise for viewing; this undoes it.)
     */
    private fun applyVisualSpace(cs: PDPageContentStream, page: PDPage, rotation: Int) {
        val box = page.cropBox
        when (rotation) {
            90 -> {
                cs.transform(Matrix.getTranslateInstance(box.upperRightX, box.lowerLeftY))
                cs.transform(Matrix.getRotateInstance(Math.toRadians(90.0), 0f, 0f))
            }
            180 -> {
                cs.transform(Matrix.getTranslateInstance(box.upperRightX, box.upperRightY))
                cs.transform(Matrix.getRotateInstance(Math.toRadians(180.0), 0f, 0f))
            }
            270 -> {
                cs.transform(Matrix.getTranslateInstance(box.lowerLeftX, box.upperRightY))
                cs.transform(Matrix.getRotateInstance(Math.toRadians(270.0), 0f, 0f))
            }
            else -> cs.transform(Matrix.getTranslateInstance(box.lowerLeftX, box.lowerLeftY))
        }
    }

    companion object {
        private const val MARGIN = 28f
    }
}
