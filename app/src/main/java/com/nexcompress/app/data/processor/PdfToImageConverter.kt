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
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): CompressionResult = withContext(Dispatchers.IO) {
        val uri = Uri.parse(input.uriString)
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        try {
            pfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw CompressionException("Unable to open the selected document.")
            renderer = PdfRenderer(pfd)

            val pageCount = renderer.pageCount
            if (pageCount <= 0) {
                throw CompressionException("This PDF contains no readable pages.")
            }

            val baseName = storage.baseNameOf(input.displayName)
            val produced = ArrayList<OutputItem>(pageCount)
            for (index in 0 until pageCount) {
                coroutineContext.ensureActive() // cooperative cancellation
                produced += renderPageToImage(renderer, index, baseName, format, quality, pageCount)
                onProgress(index + 1, pageCount)
            }
            CompressionResult(produced)
        } catch (oom: OutOfMemoryError) {
            throw CompressionException(
                "This document is too large to convert on this device. Try fewer pages or a smaller PDF."
            )
        } catch (e: CompressionException) {
            throw e
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
        pageCount: Int
    ): OutputItem {
        var page: PdfRenderer.Page? = null
        var bitmap: Bitmap? = null
        try {
            page = renderer.openPage(index)

            val width = (page.width * RENDER_SCALE).toInt().coerceAtLeast(1)
            val height = (page.height * RENDER_SCALE).toInt().coerceAtLeast(1)
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
        /** Page-point → pixel scale. 2.0 ≈ 144 DPI, a good balance of clarity vs size. */
        private const val RENDER_SCALE = 2.0f
    }
}
