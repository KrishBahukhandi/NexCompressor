package com.nexcompress.app.data.processor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.net.Uri
import com.nexcompress.app.domain.model.CompressionException
import com.nexcompress.app.domain.model.CompressionResult
import com.nexcompress.app.domain.model.FileType
import com.nexcompress.app.domain.model.ImageBatchItem
import com.nexcompress.app.domain.model.OutputItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * Images → PDF. Combines the selected images (in list order) into a single
 * multi-page PDF, one image per page. Each page is re-encoded as JPEG at the
 * chosen quality to keep the document small. Guarded against OOM / decode
 * failures; a single bad image is skipped rather than aborting the whole job.
 */
class ImagesToPdfConverter(
    private val context: Context,
    private val storage: FileStorageManager
) {

    suspend fun convert(
        items: List<ImageBatchItem>,
        outputBaseName: String,
        quality: Int
    ): CompressionResult = withContext(Dispatchers.IO) {
        if (items.isEmpty()) {
            throw CompressionException("No images were selected.")
        }

        val document = PdfDocument()
        try {
            var pagesAdded = 0
            items.forEachIndexed { index, item ->
                coroutineContext.ensureActive() // cooperative cancellation
                if (addImagePage(document, item, quality, index)) pagesAdded++
            }
            if (pagesAdded == 0) {
                throw CompressionException(
                    "None of the selected images could be added — they may be corrupted or unsupported."
                )
            }

            val outName = storage.composeOutputName(outputBaseName, "pdf")
            val saved = storage.writeOutput(outName, "application/pdf") { os ->
                document.writeTo(os)
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

    private fun addImagePage(
        document: PdfDocument,
        item: ImageBatchItem,
        quality: Int,
        index: Int
    ): Boolean {
        val uri = Uri.parse(item.source.uriString)
        var source: Bitmap? = null
        var flattened: Bitmap? = null
        var pageBitmap: Bitmap? = null
        try {
            source = decodeSampled(uri) ?: return false

            // PDF pages are JPEG-encoded, so flatten any transparency onto white.
            val toEncode = if (source.hasAlpha()) {
                flattenOntoWhite(source).also { flattened = it }
            } else {
                source
            }

            val jpeg = ByteArrayOutputStream().use { baos ->
                toEncode.compress(Bitmap.CompressFormat.JPEG, quality, baos)
                baos.toByteArray()
            }
            pageBitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return false

            val pageInfo = PdfDocument.PageInfo
                .Builder(pageBitmap.width, pageBitmap.height, index + 1)
                .create()
            val page = document.startPage(pageInfo)
            page.canvas.drawBitmap(pageBitmap, 0f, 0f, null)
            document.finishPage(page)
            return true
        } finally {
            pageBitmap?.recycle()
            flattened?.recycle()
            source?.recycle()
        }
    }

    private fun decodeSampled(uri: Uri): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = resolver.openInputStream(uri) ?: return null
        boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }

        val options = BitmapFactory.Options().apply {
            inSampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
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

    private fun computeInSampleSize(width: Int, height: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        var w = width
        var h = height
        while (w / 2 >= MAX_DIMENSION || h / 2 >= MAX_DIMENSION) {
            w /= 2; h /= 2; sample *= 2
        }
        return sample
    }

    companion object {
        /** Longest-edge ceiling per page (OOM safeguard; ~A4 @ 300 DPI). */
        private const val MAX_DIMENSION = 3000
    }
}
