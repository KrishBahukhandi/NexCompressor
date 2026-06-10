package com.nexcompress.app.data.processor

import android.content.Context
import android.net.Uri
import com.nexcompress.app.domain.model.CompressionException
import java.io.File

/**
 * Shared helper for the PDFBox-based processors. The PDF engine and the platform
 * [android.graphics.pdf.PdfRenderer] both need a real, seekable file — content
 * URIs from the Storage Access Framework aren't guaranteed to be — so we stage a
 * private copy in the app cache first. Callers must delete the returned file.
 */
internal object PdfFiles {

    fun copyToCache(context: Context, uri: Uri, prefix: String): File {
        val dir = File(context.cacheDir, "pdfwork").apply { mkdirs() }
        val out = File.createTempFile(prefix, ".pdf", dir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { input.copyTo(it) }
            } ?: throw CompressionException("Couldn't open the selected file.")
        } catch (e: CompressionException) {
            out.delete(); throw e
        } catch (e: Exception) {
            out.delete(); throw CompressionException("Couldn't read the selected file.")
        }
        return out
    }
}
