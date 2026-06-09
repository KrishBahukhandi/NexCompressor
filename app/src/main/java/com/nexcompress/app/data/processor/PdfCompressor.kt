package com.nexcompress.app.data.processor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.nexcompress.app.domain.model.CompressionException
import com.nexcompress.app.domain.model.CompressionProfile
import com.nexcompress.app.domain.model.CompressionResult
import com.nexcompress.app.domain.model.FileType
import com.nexcompress.app.domain.model.OutputItem
import com.nexcompress.app.domain.model.PickedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * PDF Compression module (PRD §2.1).
 *
 * Strategy: unpack each page with [PdfRenderer], rasterize at a down-sampled
 * resolution (DPI scaling), re-encode the raster as JPEG at the profile quality
 * to shrink heavy embedded imagery, then repack everything into a fresh
 * [PdfDocument]. Every bitmap allocation and decode is guarded against OOM and
 * document-corruption failures so the main thread never crashes.
 */
class PdfCompressor(
    private val context: Context,
    private val storage: FileStorageManager
) {

    suspend fun compress(
        input: PickedFile,
        profile: CompressionProfile,
        outputBaseName: String
    ): CompressionResult =
        withContext(Dispatchers.IO) {
            val uri = Uri.parse(input.uriString)
            var pfd: ParcelFileDescriptor? = null
            var renderer: PdfRenderer? = null
            val document = PdfDocument()
            try {
                pfd = context.contentResolver.openFileDescriptor(uri, "r")
                    ?: throw CompressionException("Unable to open the selected document.")
                renderer = PdfRenderer(pfd)

                val pageCount = renderer.pageCount
                if (pageCount <= 0) {
                    throw CompressionException("This PDF contains no readable pages.")
                }

                for (index in 0 until pageCount) {
                    coroutineContext.ensureActive() // cooperative cancellation
                    renderPageInto(renderer, document, index, profile)
                }

                // Write the rebuilt PDF to a temp file first so we can compare sizes.
                val temp = File.createTempFile("nexpdf_", ".pdf", context.cacheDir)
                val item = try {
                    FileOutputStream(temp).use { document.writeTo(it) }
                    val rasterSize = temp.length()
                    val originalSize = input.sizeBytes

                    // Rasterizing enlarges already-compact (text/vector) PDFs. Never emit
                    // a file larger than the source — fall back to copying the original.
                    val keepOriginal = originalSize in 1 until rasterSize

                    val outName = storage.composeOutputName(outputBaseName, "pdf")
                    val saved = storage.writeOutput(outName, "application/pdf") { os ->
                        if (keepOriginal) {
                            context.contentResolver.openInputStream(uri)?.use { it.copyTo(os) }
                                ?: temp.inputStream().use { it.copyTo(os) }
                        } else {
                            temp.inputStream().use { it.copyTo(os) }
                        }
                    }

                    OutputItem(
                        displayName = outName,
                        originalSize = if (originalSize > 0) originalSize else rasterSize,
                        outputSize = saved.sizeBytes,
                        uri = saved.uri.toString(),
                        type = FileType.PDF
                    )
                } finally {
                    temp.delete()
                }

                CompressionResult(listOf(item))
            } catch (oom: OutOfMemoryError) {
                // Explicit OOM guard — large sheets can saturate the JVM heap.
                throw CompressionException(
                    "This document is too large to process on this device. " +
                        "Try the Balanced profile to reduce memory usage."
                )
            } catch (e: CompressionException) {
                throw e
            } catch (e: SecurityException) {
                throw CompressionException("Permission to read this document was denied.")
            } catch (e: IOException) {
                throw CompressionException("The PDF appears to be corrupted or unreadable.")
            } catch (e: Exception) {
                throw CompressionException("This PDF could not be processed (it may be password-protected).")
            } finally {
                runCatching { document.close() }
                runCatching { renderer?.close() }
                runCatching { pfd?.close() }
            }
        }

    /** Renders one source page and appends a recompressed copy to [document]. */
    private fun renderPageInto(
        renderer: PdfRenderer,
        document: PdfDocument,
        index: Int,
        profile: CompressionProfile
    ) {
        var page: PdfRenderer.Page? = null
        var rendered: Bitmap? = null
        var recompressed: Bitmap? = null
        try {
            page = renderer.openPage(index)

            val scale = profile.renderScale
            val width = (page.width * scale).toInt().coerceAtLeast(1)
            val height = (page.height * scale).toInt().coerceAtLeast(1)

            rendered = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            // Flatten onto white so transparent regions don't render as black.
            rendered.eraseColor(Color.WHITE)
            page.render(rendered, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
            page.close()
            page = null

            // Re-encode the raster at the chosen quality to shed embedded image weight.
            val jpegBytes = ByteArrayOutputStream().use { baos ->
                rendered.compress(Bitmap.CompressFormat.JPEG, profile.quality, baos)
                baos.toByteArray()
            }
            rendered.recycle()
            rendered = null

            recompressed = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                ?: throw CompressionException("Failed to rebuild page ${index + 1}.")

            val pageInfo = PdfDocument.PageInfo
                .Builder(recompressed.width, recompressed.height, index)
                .create()
            val outPage = document.startPage(pageInfo)
            outPage.canvas.drawBitmap(recompressed, 0f, 0f, null)
            document.finishPage(outPage)
        } finally {
            runCatching { page?.close() }
            rendered?.recycle()
            recompressed?.recycle()
        }
    }
}
