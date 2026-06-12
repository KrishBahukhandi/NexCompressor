package com.nexcompress.app.data.processor

import android.content.Context
import android.net.Uri
import com.nexcompress.app.domain.model.CompressionException
import com.nexcompress.app.domain.model.CompressionResult
import com.nexcompress.app.domain.model.FileType
import com.nexcompress.app.domain.model.OutputItem
import com.nexcompress.app.domain.model.PdfPageOp
import com.nexcompress.app.domain.model.PickedFile
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lossless PDF page editor (reorder / rotate / delete) on the PDFBox engine.
 * Pages are deep-imported in the requested order, preserving selectable text and
 * vectors; only the requested rotation is layered on top of each page's own.
 */
class PdfPageEditor(
    private val context: Context,
    private val storage: FileStorageManager
) {

    /** Page count for arming the editor; 0 if the document can't be opened. */
    suspend fun pageCount(source: PickedFile): Int = withContext(Dispatchers.IO) {
        try {
            PdfPageRenderer(context, Uri.parse(source.uriString)).use { it.pageCount }
        } catch (e: Exception) {
            0
        }
    }

    suspend fun apply(
        source: PickedFile,
        ops: List<PdfPageOp>,
        outputBaseName: String
    ): CompressionResult = withContext(Dispatchers.IO) {
        if (ops.isEmpty()) {
            throw CompressionException("No pages left to export. Keep at least one page.")
        }
        val temp = PdfFiles.copyToCache(context, Uri.parse(source.uriString), "pages_")
        var src: PDDocument? = null
        var out: PDDocument? = null
        try {
            src = PDDocument.load(temp, MemoryUsageSetting.setupTempFileOnly())
            val count = src.numberOfPages
            out = PDDocument()
            ops.forEach { op ->
                if (op.sourceIndex in 0 until count) {
                    val page = src.getPage(op.sourceIndex)
                    val imported = PdfFiles.importPagePreserving(out, page)
                    imported.rotation = (page.rotation + op.rotation) % 360
                }
            }
            if (out.numberOfPages == 0) {
                throw CompressionException("No pages left to export. Keep at least one page.")
            }

            val outName = storage.composeOutputName(outputBaseName, "pdf")
            val saved = storage.writeOutput(outName, "application/pdf") { os -> out.save(os) }
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
            throw CompressionException("This PDF is too large to edit on this device.")
        } catch (e: CompressionException) {
            throw e
        } catch (e: Exception) {
            throw CompressionException("Couldn't edit this PDF. It may be corrupted or password-protected.")
        } finally {
            runCatching { out?.close() }
            runCatching { src?.close() }
            temp.delete()
        }
    }
}
