package com.nexcompress.app.data.processor

import android.content.Context
import android.net.Uri
import com.nexcompress.app.domain.model.CompressionException
import com.nexcompress.app.domain.model.CompressionResult
import com.nexcompress.app.domain.model.FileType
import com.nexcompress.app.domain.model.OutputItem
import com.nexcompress.app.domain.model.PickedFile
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Losslessly concatenates several PDFs (in list order) into one document using
 * PDFBox's merge utility. Each source is staged to a private cache file first.
 */
class PdfMerger(
    private val context: Context,
    private val storage: FileStorageManager
) {

    suspend fun merge(
        items: List<PickedFile>,
        outputBaseName: String
    ): CompressionResult = withContext(Dispatchers.IO) {
        if (items.size < 2) {
            throw CompressionException("Pick at least two PDFs to merge.")
        }
        val temps = ArrayList<File>(items.size)
        try {
            items.forEach { temps += PdfFiles.copyToCache(context, Uri.parse(it.uriString), "merge_") }

            val outName = storage.composeOutputName(outputBaseName, "pdf")
            val merger = PDFMergerUtility()
            val saved = storage.writeOutput(outName, "application/pdf") { os ->
                merger.destinationStream = os
                temps.forEach { merger.addSource(it) }
                merger.mergeDocuments(MemoryUsageSetting.setupTempFileOnly())
            }

            CompressionResult(
                listOf(
                    OutputItem(
                        displayName = outName,
                        originalSize = items.sumOf { it.sizeBytes },
                        outputSize = saved.sizeBytes,
                        uri = saved.uri.toString(),
                        type = FileType.PDF
                    )
                )
            )
        } catch (oom: OutOfMemoryError) {
            throw CompressionException("These PDFs are too large to merge on this device.")
        } catch (e: CompressionException) {
            throw e
        } catch (e: Exception) {
            throw CompressionException("Couldn't merge these PDFs. One may be corrupted or password-protected.")
        } finally {
            temps.forEach { it.delete() }
        }
    }
}
