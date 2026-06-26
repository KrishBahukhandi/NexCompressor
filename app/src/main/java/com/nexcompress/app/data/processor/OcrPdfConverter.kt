package com.nexcompress.app.data.processor

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.nexcompress.app.domain.model.CompressionException
import com.nexcompress.app.domain.model.CompressionResult
import com.nexcompress.app.domain.model.FileType
import com.nexcompress.app.domain.model.OutputItem
import com.nexcompress.app.domain.model.PickedFile
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Makes a scanned PDF searchable, fully on-device. Each image-only page is OCR'd
 * (Google ML Kit) and an INVISIBLE, word-positioned text layer is laid over the
 * page so the text becomes selectable and searchable.
 *
 * Crucially, the page's **original image is preserved at full quality** — we only
 * render a copy to feed the OCR, then throw it away and overlay the text onto the
 * untouched page. (The old approach re-rastered every page to a JPEG, softening a
 * crisp 300-dpi scan; this keeps it pixel-for-pixel.) Pages that already carry a
 * real text layer are passed straight through. A page with a non-zero /Rotate is
 * handled by the raster fallback, where the renderer resolves the rotation for us.
 */
class OcrPdfConverter(
    private val context: Context,
    private val storage: FileStorageManager
) {
    suspend fun convert(
        input: PickedFile,
        outputBaseName: String,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): CompressionResult = withContext(Dispatchers.IO) {
        val uri = Uri.parse(input.uriString)
        val temp = PdfFiles.copyToCache(context, uri, "ocr_")
        val rebuilt = File.createTempFile("nexocr_", ".pdf", context.cacheDir)
        var src: PDDocument? = null
        var out: PDDocument? = null
        var renderer: PdfPageRenderer? = null
        try {
            src = PDDocument.load(temp, MemoryUsageSetting.setupTempFileOnly())
            if (src.isEncrypted) {
                throw CompressionException(
                    "This PDF is password-protected. Unlock it first via Protect PDF."
                )
            }
            val total = src.numberOfPages
            if (total <= 0) throw CompressionException("This PDF has no pages.")

            val pageHasText = detectTextPages(src)
            out = PDDocument()
            // A Unicode font lets the hidden layer carry accented Latin and other
            // scripts the OCR returns (é, ñ, ü, ç…) instead of stripping to ASCII.
            // Falls back to a standard font (ASCII-only) if no system TTF is found.
            val hiddenFont = loadUnicodeFont(out)
            val unicode = hiddenFont is PDType0Font
            val font: PDFont = hiddenFont ?: PDType1Font.HELVETICA
            renderer = PdfPageRenderer(context, uri)
            // Count the image-only pages we treat as scans. The "nothing to OCR"
            // message must depend on whether any page lacked text — NOT on whether
            // OCR happened to recover words (a hard-to-read scan still gets output).
            var scannedPages = 0
            for (i in 0 until total) {
                coroutineContext.ensureActive()
                val srcPage = src.getPage(i)
                when {
                    pageHasText[i] -> PdfFiles.importPagePreserving(out, srcPage)
                    normalizedRotation(srcPage) == 0 -> {
                        // Preserve the original page; overlay only the hidden text.
                        val imported = PdfFiles.importPagePreserving(out, srcPage)
                        overlaySearchableText(out, imported, renderer, i, font, unicode)
                        scannedPages++
                    }
                    else -> {
                        // Rotated page → raster rebuild (handles /Rotate correctly).
                        buildRasterSearchablePage(out, renderer, srcPage, i, font, unicode)
                        scannedPages++
                    }
                }
                onProgress(i + 1, total)
            }
            if (scannedPages == 0) {
                throw CompressionException(
                    "This PDF already has selectable text — there's nothing to OCR."
                )
            }

            FileOutputStream(rebuilt).use { out.save(it) }
            val outName = storage.composeOutputName(outputBaseName, "pdf")
            val saved = storage.writeOutput(outName, "application/pdf") { os ->
                rebuilt.inputStream().use { it.copyTo(os) }
            }
            CompressionResult(
                listOf(
                    OutputItem(
                        displayName = outName,
                        originalSize = input.sizeBytes,
                        outputSize = saved.sizeBytes,
                        uri = saved.uri.toString(),
                        type = FileType.PDF
                    )
                )
            )
        } catch (e: InvalidPasswordException) {
            throw CompressionException("This PDF is password-protected. Unlock it first via Protect PDF.")
        } catch (oom: OutOfMemoryError) {
            throw CompressionException("This PDF is too large to OCR on this device.")
        } catch (e: CompressionException) {
            throw e
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            throw CompressionException("Couldn't OCR this PDF. It may be corrupted.")
        } finally {
            runCatching { out?.close() }
            runCatching { src?.close() }
            runCatching { renderer?.close() }
            rebuilt.delete()
            temp.delete()
        }
    }

    /** Per-page flag: true when the page already carries an embedded text layer. */
    private fun detectTextPages(doc: PDDocument): BooleanArray {
        val flags = BooleanArray(doc.numberOfPages)
        val stripper = PDFTextStripper()
        for (i in flags.indices) {
            stripper.startPage = i + 1
            stripper.endPage = i + 1
            flags[i] = runCatching { stripper.getText(doc).trim().length >= MIN_TEXT_CHARS }
                .getOrDefault(false)
        }
        return flags
    }

    /**
     * Renders the page only to OCR it, then overlays an invisible word layer onto
     * the *preserved* page ([page]), mapping word pixels to the page's user space.
     * Returns true if any text was recovered. The render is discarded.
     */
    private fun overlaySearchableText(
        out: PDDocument,
        page: PDPage,
        renderer: PdfPageRenderer,
        index: Int,
        font: PDFont,
        unicode: Boolean
    ) {
        val bmp = renderer.renderPage(index, RENDER_LONG_EDGE) ?: return
        try {
            val words = OcrEngine.recognizeWords(bmp)
            if (words.isEmpty()) return
            val box = page.cropBox
            val sx = box.width / bmp.width
            val sy = box.height / bmp.height
            PDPageContentStream(
                out, page, PDPageContentStream.AppendMode.APPEND, true, true
            ).use { cs ->
                cs.setRenderingMode(RenderingMode.NEITHER) // mode 3 — invisible
                cs.setNonStrokingColor(0f, 0f, 0f)
                for (word in words) {
                    val text = sanitize(word.text, unicode)
                    if (text.isBlank()) continue
                    val fontSize = (word.height * sy).coerceIn(4f, 400f)
                    val x = box.lowerLeftX + word.left * sx
                    val baseline =
                        box.lowerLeftY + box.height - (word.top + word.height) * sy + fontSize * 0.18f
                    drawHidden(cs, font, text, x, baseline, fontSize)
                }
            }
        } finally {
            bmp.recycle()
        }
    }

    /**
     * Fallback for /Rotate'd pages: render the page upright, write it back as the
     * page image, and overlay the hidden text in that same (upright) space.
     */
    private fun buildRasterSearchablePage(
        out: PDDocument,
        renderer: PdfPageRenderer,
        srcPage: PDPage,
        index: Int,
        font: PDFont,
        unicode: Boolean
    ) {
        val bmp = renderer.renderPage(index, RENDER_LONG_EDGE)
            ?: throw CompressionException("Couldn't render page ${index + 1}.")
        try {
            val words = OcrEngine.recognizeWords(bmp)
            val (vw, vh) = visualSizePoints(srcPage)
            val page = PDPage(PDRectangle(vw, vh))
            out.addPage(page)
            val image = JPEGFactory.createFromImage(out, bmp, RASTER_IMAGE_QUALITY)
            PDPageContentStream(out, page).use { cs -> cs.drawImage(image, 0f, 0f, vw, vh) }
            if (words.isEmpty()) return
            val sx = vw / bmp.width
            val sy = vh / bmp.height
            PDPageContentStream(
                out, page, PDPageContentStream.AppendMode.APPEND, true, true
            ).use { cs ->
                cs.setRenderingMode(RenderingMode.NEITHER)
                cs.setNonStrokingColor(0f, 0f, 0f)
                for (word in words) {
                    val text = sanitize(word.text, unicode)
                    if (text.isBlank()) continue
                    val fontSize = (word.height * sy).coerceIn(4f, 400f)
                    val x = word.left * sx
                    val baseline = vh - (word.top + word.height) * sy + fontSize * 0.18f
                    drawHidden(cs, font, text, x, baseline, fontSize)
                }
            }
        } finally {
            bmp.recycle()
        }
    }

    private fun drawHidden(
        cs: PDPageContentStream,
        font: PDFont,
        text: String,
        x: Float,
        baseline: Float,
        size: Float
    ) {
        cs.beginText()
        cs.setFont(font, size)
        cs.newLineAtOffset(x, baseline)
        // A glyph the font can't encode would throw; skip that word rather than fail.
        runCatching { cs.showText(text) }
        cs.endText()
    }

    /** Keep the OCR text as-is for a Unicode font; otherwise reduce to ASCII. */
    private fun sanitize(raw: String, unicode: Boolean): String =
        (if (unicode) raw else PdfText.asciiSafe(raw)).trim()

    /** Loads a system Unicode TTF for the hidden text layer, or null if unavailable. */
    private fun loadUnicodeFont(doc: PDDocument): PDType0Font? {
        for (path in SYSTEM_FONTS) {
            val file = File(path)
            if (!file.exists()) continue
            val font = runCatching {
                file.inputStream().use { PDType0Font.load(doc, it, true) }
            }.getOrNull()
            if (font != null) return font
        }
        return null
    }

    private fun normalizedRotation(page: PDPage): Int = ((page.rotation % 360) + 360) % 360

    private fun visualSizePoints(page: PDPage): Pair<Float, Float> {
        val box = page.cropBox
        return if (normalizedRotation(page).let { it == 90 || it == 270 }) box.height to box.width
        else box.width to box.height
    }

    companion object {
        /** Render resolution (longest edge, px) used to OCR a scanned page. */
        private const val RENDER_LONG_EDGE = 2200

        /** JPEG quality for the rotated-page fallback's re-rendered image. */
        private const val RASTER_IMAGE_QUALITY = 0.85f

        /** A page with at least this much extractable text is treated as text, not a scan. */
        private const val MIN_TEXT_CHARS = 8

        /** Candidate system TTFs (broad Latin+ coverage) for the hidden text layer. */
        private val SYSTEM_FONTS = listOf(
            "/system/fonts/Roboto-Regular.ttf",
            "/system/fonts/NotoSans-Regular.ttf",
            "/system/fonts/DroidSans.ttf"
        )
    }
}
