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
 */
class ImagesToPdfConverter(
    private val context: Context,
    private val storage: FileStorageManager
) {

    suspend fun convert(
        items: List<ImageBatchItem>,
        outputBaseName: String,
        quality: Int,
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
                if (addImagePage(document, item, quality)) pagesAdded++
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
        } catch (e: Exception) {
            throw CompressionException("Couldn't create the PDF from these images.")
        } finally {
            runCatching { document.close() }
        }
    }

    private fun addImagePage(document: PDDocument, item: ImageBatchItem, quality: Int): Boolean {
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

            val w = toEncode.width.toFloat()
            val h = toEncode.height.toFloat()
            // Build the image + content stream BEFORE attaching the page, so a
            // failure here can't leave a blank page in the saved document.
            val page = PDPage(PDRectangle(w, h))
            val image = JPEGFactory.createFromStream(document, ByteArrayInputStream(jpeg))
            PDPageContentStream(document, page).use { cs ->
                cs.drawImage(image, 0f, 0f, w, h)
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
    }
}
