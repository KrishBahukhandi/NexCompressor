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

    private val tempFile: File = PdfFiles.copyToCache(context, uri, "render_")
    private val pfd: ParcelFileDescriptor =
        ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer: PdfRenderer = PdfRenderer(pfd)
    private val lock = Any()

    val pageCount: Int get() = renderer.pageCount

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
            runCatching { tempFile.delete() }
        }
    }
}
