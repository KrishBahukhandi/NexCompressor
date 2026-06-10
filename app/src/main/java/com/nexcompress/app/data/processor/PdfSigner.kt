package com.nexcompress.app.data.processor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.net.Uri
import com.nexcompress.app.domain.model.CompressionException
import com.nexcompress.app.domain.model.CompressionResult
import com.nexcompress.app.domain.model.FileType
import com.nexcompress.app.domain.model.OutputItem
import com.nexcompress.app.domain.model.PickedFile
import com.nexcompress.app.domain.model.SignaturePlacement
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Stamps a hand-drawn signature onto a PDF page. The target page is rendered
 * upright at high resolution (so rotation is handled correctly for any page),
 * the signature is composited at the user's normalized placement, and the result
 * is re-assembled with PDFBox — the signed page becomes a crisp image while every
 * *other* page is copied losslessly (selectable text preserved).
 */
class PdfSigner(
    private val context: Context,
    private val storage: FileStorageManager
) {

    suspend fun sign(
        source: PickedFile,
        signaturePng: ByteArray,
        placement: SignaturePlacement,
        outputBaseName: String
    ): CompressionResult = withContext(Dispatchers.IO) {
        val uri = Uri.parse(source.uriString)
        val temp = PdfFiles.copyToCache(context, uri, "sign_")
        var renderer: PdfPageRenderer? = null
        var pageBitmap: Bitmap? = null
        var sigBitmap: Bitmap? = null
        var src: PDDocument? = null
        var out: PDDocument? = null
        try {
            renderer = PdfPageRenderer(context, uri)
            val count = renderer.pageCount
            if (placement.pageIndex !in 0 until count) {
                throw CompressionException("That page no longer exists in the document.")
            }

            // 1) Render the target page upright, then composite the signature on top.
            pageBitmap = renderer.renderPage(placement.pageIndex, SIGNED_PAGE_LONG_EDGE)
                ?: throw CompressionException("Couldn't render the page to sign.")
            sigBitmap = BitmapFactory.decodeByteArray(signaturePng, 0, signaturePng.size)
                ?: throw CompressionException("Couldn't read the signature.")

            val pw = pageBitmap.width
            val ph = pageBitmap.height
            val l = (placement.left * pw).toInt()
            val t = (placement.top * ph).toInt()
            val w = (placement.width * pw).toInt().coerceAtLeast(1)
            val h = (placement.height * ph).toInt().coerceAtLeast(1)
            Canvas(pageBitmap).drawBitmap(sigBitmap, null, Rect(l, t, l + w, t + h), null)

            // 2) Rebuild: signed page -> image; all other pages copied losslessly.
            src = PDDocument.load(temp, MemoryUsageSetting.setupTempFileOnly())
            out = PDDocument()
            for (i in 0 until src.numberOfPages) {
                if (i == placement.pageIndex) {
                    val (vw, vh) = visualSizePoints(src.getPage(i))
                    val page = PDPage(PDRectangle(vw, vh))
                    out.addPage(page)
                    val image = JPEGFactory.createFromImage(out, pageBitmap, JPEG_QUALITY)
                    PDPageContentStream(out, page).use { cs ->
                        cs.drawImage(image, 0f, 0f, vw, vh)
                    }
                } else {
                    out.importPage(src.getPage(i))
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
        } catch (oom: OutOfMemoryError) {
            throw CompressionException("This PDF is too large to sign on this device.")
        } catch (e: CompressionException) {
            throw e
        } catch (e: Exception) {
            throw CompressionException("Couldn't add the signature to this PDF. It may be password-protected.")
        } finally {
            sigBitmap?.recycle()
            pageBitmap?.recycle()
            runCatching { out?.close() }
            runCatching { src?.close() }
            runCatching { renderer?.close() }
            temp.delete()
        }
    }

    /** The page's visible size in points, accounting for its /Rotate. */
    private fun visualSizePoints(page: PDPage): Pair<Float, Float> {
        val box = page.cropBox
        val rotation = ((page.rotation % 360) + 360) % 360
        return if (rotation == 90 || rotation == 270) {
            box.height to box.width
        } else {
            box.width to box.height
        }
    }

    companion object {
        /** Render resolution of the signed page (longest edge, px). */
        private const val SIGNED_PAGE_LONG_EDGE = 1800
        private const val JPEG_QUALITY = 0.9f
    }
}
