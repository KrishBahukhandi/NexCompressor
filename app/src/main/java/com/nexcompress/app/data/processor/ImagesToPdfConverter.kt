package com.nexcompress.app.data.processor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import com.nexcompress.app.domain.model.CompressionException
import com.nexcompress.app.domain.model.CompressionResult
import com.nexcompress.app.domain.model.FileType
import com.nexcompress.app.domain.model.ImageBatchItem
import com.nexcompress.app.domain.model.OutputItem
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.coroutines.coroutineContext

/**
 * Images → PDF. Combines the selected images (in list order) into a single
 * multi-page PDF, one image per page. Photos are decoded upright (EXIF applied)
 * and embedded as JPEG streams at the chosen quality, so the quality slider
 * directly controls the output size. A single bad image is skipped rather than
 * aborting the whole job.
 *
 * Each image is fitted onto a standard **A4** page (in the image's orientation,
 * centered with a small margin) so the output prints/opens like the major
 * converters — unless [fitToA4] is off, in which case the page matches the
 * image's exact pixel dimensions.
 */
class ImagesToPdfConverter(
    private val context: Context,
    private val storage: FileStorageManager
) {

    suspend fun convert(
        items: List<ImageBatchItem>,
        outputBaseName: String,
        quality: Int,
        fitToA4: Boolean = true,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): CompressionResult = withContext(Dispatchers.IO) {
        if (items.isEmpty()) {
            throw CompressionException("No images were selected.")
        }

        val document = PDDocument()
        try {
            var pagesAdded = 0
            items.forEachIndexed { index, item ->
                coroutineContext.ensureActive() // cooperative cancellation
                if (addImagePage(document, item, quality, fitToA4)) pagesAdded++
                onProgress(index + 1, items.size)
            }
            if (pagesAdded == 0) {
                throw CompressionException(
                    "None of the selected images could be added — they may be corrupted or unsupported."
                )
            }

            val outName = storage.composeOutputName(outputBaseName, "pdf")
            val saved = storage.writeOutput(outName, "application/pdf") { os ->
                document.save(os)
            }

            // A conversion produces a new asset rather than savings (original == output).
            CompressionResult(
                listOf(
                    OutputItem(
                        displayName = outName,
                        originalSize = saved.sizeBytes,
                        outputSize = saved.sizeBytes,
                        uri = saved.uri.toString(),
                        type = FileType.PDF
                    )
                )
            )
        } catch (oom: OutOfMemoryError) {
            throw CompressionException("These images are too large to combine on this device.")
        } catch (e: CompressionException) {
            throw e
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            throw CompressionException("Couldn't create the PDF from these images.")
        } finally {
            runCatching { document.close() }
        }
    }

    private fun addImagePage(
        document: PDDocument,
        item: ImageBatchItem,
        quality: Int,
        fitToA4: Boolean
    ): Boolean {
        val uri = Uri.parse(item.source.uriString)
        var source: Bitmap? = null
        var flattened: Bitmap? = null
        try {
            var work = ImageDecoding.decodeUpright(context, uri, MAX_DIMENSION) ?: return false
            source = work

            // Apply any per-image edit (rotate / flip / crop / resize) first.
            item.editSpec?.let {
                work = ImageTransforms.applyGeometry(work, it)
                source = work
            }

            // Pages embed JPEG, so flatten any transparency onto white.
            val toEncode = if (work.hasAlpha()) {
                flattenOntoWhite(work).also { flattened = it }
            } else {
                work
            }

            val jpeg = ByteArrayOutputStream().use { baos ->
                toEncode.compress(Bitmap.CompressFormat.JPEG, quality, baos)
                baos.toByteArray()
            }
            if (jpeg.isEmpty()) return false

            val imgW = toEncode.width.toFloat()
            val imgH = toEncode.height.toFloat()
            // Build the image + content stream BEFORE attaching the page, so a
            // failure here can't leave a blank page in the saved document.
            val image = JPEGFactory.createFromStream(document, ByteArrayInputStream(jpeg))
            val page = if (fitToA4) {
                // A4 in the image's orientation, image scaled to fit & centered.
                val landscape = imgW > imgH
                val pageW = if (landscape) A4_LONG else A4_SHORT
                val pageH = if (landscape) A4_SHORT else A4_LONG
                val scale = minOf((pageW - 2 * PAGE_MARGIN) / imgW, (pageH - 2 * PAGE_MARGIN) / imgH)
                val drawW = imgW * scale
                val drawH = imgH * scale
                PDPage(PDRectangle(pageW, pageH)).also { p ->
                    PDPageContentStream(document, p).use { cs ->
                        cs.drawImage(image, (pageW - drawW) / 2f, (pageH - drawH) / 2f, drawW, drawH)
                    }
                }
            } else {
                PDPage(PDRectangle(imgW, imgH)).also { p ->
                    PDPageContentStream(document, p).use { cs ->
                        cs.drawImage(image, 0f, 0f, imgW, imgH)
                    }
                }
            }
            document.addPage(page)
            return true
        } catch (oom: OutOfMemoryError) {
            return false
        } catch (e: Exception) {
            return false
        } finally {
            flattened?.recycle()
            source?.recycle()
        }
    }

    private fun flattenOntoWhite(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.WHITE)
            drawBitmap(src, 0f, 0f, null)
        }
        return out
    }

    companion object {
        /** Longest-edge ceiling per page (OOM safeguard; ~A4 @ 300 DPI). */
        private const val MAX_DIMENSION = 3000

        // A4 in points (1/72"), plus a comfortable margin, for fitted pages.
        private const val A4_SHORT = 595f
        private const val A4_LONG = 842f
        private const val PAGE_MARGIN = 18f
    }
}
