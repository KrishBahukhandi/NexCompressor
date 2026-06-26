package com.nexcompress.app.data.processor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import com.nexcompress.app.domain.model.CompressionException
import com.nexcompress.app.domain.model.CompressionResult
import com.nexcompress.app.domain.model.FileType
import com.nexcompress.app.domain.model.ImageFormat
import com.nexcompress.app.domain.model.OutputItem
import com.nexcompress.app.domain.model.PickedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * PDF → Images conversion. Rasterizes each PDF page with [PdfRenderer] and writes
 * it out as JPG / PNG / WebP. One output image per page. All bitmap work is guarded
 * against OOM and document-corruption so the main thread never crashes.
 */
class PdfToImageConverter(
    private val context: Context,
    private val storage: FileStorageManager
) {

    suspend fun convert(
        input: PickedFile,
        format: ImageFormat,
        quality: Int,
        dpi: Int = DEFAULT_DPI,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): CompressionResult = withContext(Dispatchers.IO) {
        val uri = Uri.parse(input.uriString)
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        val produced = ArrayList<OutputItem>()
        try {
            pfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw CompressionException("Unable to open the selected document.")
            renderer = PdfRenderer(pfd)

            val pageCount = renderer.pageCount
            if (pageCount <= 0) {
                throw CompressionException("This PDF contains no readable pages.")
            }
            if (pageCount > MAX_PAGES) {
                // Without this, a 20,000-page PDF would try to write 20,000 image
                // files into Downloads — flooding storage and running for ages.
                throw CompressionException(
                    "This PDF has $pageCount pages. Exporting more than $MAX_PAGES images " +
                        "at once isn't supported — split it into smaller files first."
                )
            }

            // PDF user space is 72 dpi, so scale = target dpi / 72 (clamped for OOM).
            val scale = (dpi.toFloat() / 72f).coerceIn(MIN_SCALE, MAX_SCALE)
            val baseName = storage.baseNameOf(input.displayName)
            produced.ensureCapacity(pageCount)
            for (index in 0 until pageCount) {
                coroutineContext.ensureActive() // cooperative cancellation
                produced += renderPageToImage(renderer, index, baseName, format, quality, pageCount, scale)
                onProgress(index + 1, pageCount)
            }
            CompressionResult(produced)
        } catch (oom: OutOfMemoryError) {
            throw CompressionException(
                "This document is too large to convert at this resolution. Try a lower DPI or a smaller PDF."
            )
        } catch (e: CompressionException) {
            throw e
        } catch (c: kotlinx.coroutines.CancellationException) {
            // Cancelled mid-run — remove the page images already written.
            produced.forEach { storage.deleteOutput(it.uri) }
            throw c
        } catch (e: SecurityException) {
            // PdfRenderer signals encrypted documents this way too.
            throw CompressionException(
                "This PDF couldn't be opened — it may be password-protected " +
                    "(unlock it first via Protect PDF) or no longer accessible."
            )
        } catch (e: IOException) {
            throw CompressionException("The PDF appears to be corrupted or unreadable.")
        } catch (e: Exception) {
            throw CompressionException("This PDF could not be converted (it may be password-protected).")
        } finally {
            runCatching { renderer?.close() }
            runCatching { pfd?.close() }
        }
    }

    private fun renderPageToImage(
        renderer: PdfRenderer,
        index: Int,
        baseName: String,
        format: ImageFormat,
        quality: Int,
        pageCount: Int,
        scale: Float
    ): OutputItem {
        var page: PdfRenderer.Page? = null
        var bitmap: Bitmap? = null
        try {
            page = renderer.openPage(index)

            val width = (page.width * scale).toInt().coerceAtLeast(1)
            val height = (page.height * scale).toInt().coerceAtLeast(1)
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            // Flatten onto white so transparent regions don't render black (esp. JPEG).
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()
            page = null

            val label = if (pageCount > 1) "${baseName}_page_${index + 1}" else baseName
            val outName = storage.composeOutputName(label, format.extension)
            val saved = storage.writeOutput(outName, format.mimeType) { os ->
                val ok = bitmap!!.compress(compressFormatFor(format), quality, os)
                if (!ok) throw IOException("Encoder rejected page ${index + 1}.")
            }

            // A conversion produces new assets rather than savings, so original == output.
            return OutputItem(
                displayName = outName,
                originalSize = saved.sizeBytes,
                outputSize = saved.sizeBytes,
                uri = saved.uri.toString(),
                type = FileType.IMAGE
            )
        } finally {
            runCatching { page?.close() }
            bitmap?.recycle()
        }
    }

    private fun compressFormatFor(format: ImageFormat): Bitmap.CompressFormat = when (format) {
        ImageFormat.JPEG -> Bitmap.CompressFormat.JPEG
        ImageFormat.PNG -> Bitmap.CompressFormat.PNG
        ImageFormat.WEBP ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }
    }

    companion object {
        /** Max pages exportable as images in one job (storage / time safeguard). */
        private const val MAX_PAGES = 500

        /** Default export resolution (dots per inch). 150 dpi prints cleanly. */
        const val DEFAULT_DPI = 150

        /** Render-scale clamp (≈72–432 dpi) so a huge DPI can't blow the heap. */
        private const val MIN_SCALE = 1.0f
        private const val MAX_SCALE = 6.0f
    }
}
