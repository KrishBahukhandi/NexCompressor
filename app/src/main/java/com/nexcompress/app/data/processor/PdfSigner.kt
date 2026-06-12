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
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Stamps a hand-drawn signature onto a PDF page.
 *
 * Primary path: the transparent signature PNG is appended to the target page's
 * content stream as an image overlay (positioned to match the user's normalized
 * placement on the *displayed* page, compensating for any /Rotate). The whole
 * document — including the signed page's text — stays lossless and selectable,
 * and the file only grows by the size of the signature image.
 *
 * Fallback (documents PDFBox can't parse): the signed page is rendered upright
 * at high resolution with the signature composited on top, while every other
 * page is copied losslessly.
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
        try {
            val outName = storage.composeOutputName(outputBaseName, "pdf")
            val saved = try {
                stampOverlay(temp, signaturePng, placement, outName)
            } catch (e: InvalidPasswordException) {
                throw CompressionException(
                    "This PDF is password-protected. Unlock it first via Protect PDF, then sign it."
                )
            } catch (e: CompressionException) {
                throw e
            } catch (oom: OutOfMemoryError) {
                throw CompressionException("This PDF is too large to sign on this device.")
            } catch (e: Exception) {
                // Unparseable structure — flatten just the signed page instead.
                stampRasterized(uri, temp, signaturePng, placement, outName)
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
        } catch (oom: OutOfMemoryError) {
            throw CompressionException("This PDF is too large to sign on this device.")
        } catch (e: CompressionException) {
            throw e
        } catch (e: Exception) {
            throw CompressionException("Couldn't add the signature to this PDF. It may be password-protected.")
        } finally {
            temp.delete()
        }
    }

    /** Draws the signature into the page's content stream; everything stays lossless. */
    private fun stampOverlay(
        stagedPdf: File,
        signaturePng: ByteArray,
        placement: SignaturePlacement,
        outName: String
    ): FileStorageManager.SavedFile {
        PDDocument.load(stagedPdf, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
            if (doc.isEncrypted) {
                throw CompressionException(
                    "This PDF is password-protected. Unlock it first via Protect PDF, then sign it."
                )
            }
            if (placement.pageIndex !in 0 until doc.numberOfPages) {
                throw CompressionException("That page no longer exists in the document.")
            }
            val page = doc.getPage(placement.pageIndex)

            val sigBitmap = BitmapFactory.decodeByteArray(signaturePng, 0, signaturePng.size)
                ?: throw CompressionException("Couldn't read the signature.")
            val sigImage = try {
                // Lossless keeps the PNG's alpha, so only the ink covers the page.
                LosslessFactory.createFromImage(doc, sigBitmap)
            } finally {
                sigBitmap.recycle()
            }

            // The user placed the signature on the *displayed* (upright) page;
            // map that into unrotated PDF user space.
            val box = page.cropBox
            val rotation = ((page.rotation % 360) + 360) % 360
            val pw = box.width
            val ph = box.height
            val vw = if (rotation == 90 || rotation == 270) ph else pw
            val vh = if (rotation == 90 || rotation == 270) pw else ph

            val xu = placement.left * vw
            val yu = (1f - placement.top - placement.height) * vh // top-left -> bottom-left origin
            val wu = placement.width * vw
            val hu = placement.height * vh

            val ox = box.lowerLeftX
            val oy = box.lowerLeftY
            // Unit square -> placed rectangle, pre-rotated so the signature reads
            // upright once the viewer applies the page's /Rotate.
            val m = when (rotation) {
                90 -> Matrix(0f, wu, -hu, 0f, ox + pw - yu, oy + xu)
                180 -> Matrix(-wu, 0f, 0f, -hu, ox + pw - xu, oy + ph - yu)
                270 -> Matrix(0f, -wu, hu, 0f, ox + yu, oy + ph - xu)
                else -> Matrix(wu, 0f, 0f, hu, ox + xu, oy + yu)
            }

            PDPageContentStream(
                doc, page, PDPageContentStream.AppendMode.APPEND, true, true
            ).use { cs ->
                cs.drawImage(sigImage, m)
            }

            return storage.writeOutput(outName, "application/pdf") { os -> doc.save(os) }
        }
    }

    /**
     * Fallback: renders the target page upright at high resolution, composites
     * the signature, and rebuilds the document — the signed page becomes an
     * image while every other page is copied losslessly.
     */
    private fun stampRasterized(
        uri: Uri,
        stagedPdf: File,
        signaturePng: ByteArray,
        placement: SignaturePlacement,
        outName: String
    ): FileStorageManager.SavedFile {
        var renderer: PdfPageRenderer? = null
        var pageBitmap: Bitmap? = null
        var sigBitmap: Bitmap? = null
        var src: PDDocument? = null
        var out: PDDocument? = null
        try {
            renderer = PdfPageRenderer(context, uri)
            if (placement.pageIndex !in 0 until renderer.pageCount) {
                throw CompressionException("That page no longer exists in the document.")
            }

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

            src = PDDocument.load(stagedPdf, MemoryUsageSetting.setupTempFileOnly())
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
                    PdfFiles.importPagePreserving(out, src.getPage(i))
                }
            }

            return storage.writeOutput(outName, "application/pdf") { os -> out.save(os) }
        } finally {
            sigBitmap?.recycle()
            pageBitmap?.recycle()
            runCatching { out?.close() }
            runCatching { src?.close() }
            runCatching { renderer?.close() }
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
        /** Render resolution of the signed page in the raster fallback (longest edge, px). */
        private const val SIGNED_PAGE_LONG_EDGE = 1800
        private const val JPEG_QUALITY = 0.9f
    }
}
