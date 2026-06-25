package com.nexcompress.app.data.processor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File

/**
 * On-demand PDF page rasterizer backed by the platform [PdfRenderer], used for
 * editor thumbnails / previews and for flattening a signed page. The source URI
 * is staged to a private cache file once (PdfRenderer needs a seekable
 * descriptor); pages are then rendered upright (the renderer applies each page's
 * own /Rotate). Renders are serialized — PdfRenderer allows only one open page at
 * a time. Always [close] when finished.
 */
class PdfPageRenderer(context: Context, uri: Uri) : Closeable {

    private val tempFile: File?
    private val pfd: ParcelFileDescriptor
    private val renderer: PdfRenderer
    private val lock = Any()

    init {
        var tmp: File? = null
        var descriptor: ParcelFileDescriptor? = null
        var pdf: PdfRenderer? = null
        try {
            // Fast path: render straight from the provider's descriptor — no full
            // file copy (callers that also stage for PDFBox were copying twice).
            // PdfRenderer needs a *seekable* fd; if a provider gives a non-seekable
            // one (some cloud providers), fall back to a staged private copy.
            descriptor = context.contentResolver.openFileDescriptor(uri, "r")
            if (descriptor != null) {
                pdf = runCatching { PdfRenderer(descriptor) }.getOrNull()
                if (pdf == null) {
                    runCatching { descriptor.close() }
                    descriptor = null
                }
            }
            if (pdf == null) {
                tmp = PdfFiles.copyToCache(context, uri, "render_")
                descriptor = ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY)
                pdf = PdfRenderer(descriptor)
            }
        } catch (t: Throwable) {
            runCatching { pdf?.close() }
            runCatching { descriptor?.close() }
            tmp?.delete()
            throw t
        }
        tempFile = tmp
        pfd = descriptor!!
        renderer = pdf!!
    }

    val pageCount: Int get() = renderer.pageCount

    /**
     * The page's visible (upright) height in PDF points (1/72"). Lets callers map
     * a point font size to a fraction of page height. Returns 0 on failure.
     */
    fun visualPointHeight(index: Int): Int {
        synchronized(lock) {
            if (index < 0 || index >= renderer.pageCount) return 0
            return runCatching { renderer.openPage(index).use { it.height } }.getOrDefault(0)
        }
    }

    /** Renders [index] so its longest edge is ~[targetLongEdgePx] px, white-backed. */
    fun renderPage(index: Int, targetLongEdgePx: Int): Bitmap? {
        synchronized(lock) {
            if (index < 0 || index >= renderer.pageCount) return null
            renderer.openPage(index).use { page ->
                val longest = maxOf(page.width, page.height).coerceAtLeast(1)
                val scale = targetLongEdgePx.toFloat() / longest
                val w = (page.width * scale).toInt().coerceAtLeast(1)
                val h = (page.height * scale).toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                return bmp
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            runCatching { renderer.close() }
            runCatching { pfd.close() }
            runCatching { tempFile?.delete() } // null on the fast (no-copy) path
        }
    }
}
