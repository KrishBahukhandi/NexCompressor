package com.nexcompress.app.data.processor

import android.content.Context
import android.net.Uri
import com.nexcompress.app.domain.model.CompressionException
import com.nexcompress.app.domain.model.CompressionResult
import com.nexcompress.app.domain.model.FileType
import com.nexcompress.app.domain.model.OutputItem
import com.nexcompress.app.domain.model.PickedFile
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Splits a PDF on the PDFBox engine — either pulling a chosen set of pages into a
 * single new document, or exploding every page into its own one-page PDF. Output
 * pages keep their original (selectable) content.
 */
class PdfSplitter(
    private val context: Context,
    private val storage: FileStorageManager
) {

    /** Page count for arming the selector; 0 if the document can't be opened. */
    suspend fun pageCount(source: PickedFile): Int = withContext(Dispatchers.IO) {
        try {
            PdfPageRenderer(context, Uri.parse(source.uriString)).use { it.pageCount }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            // Including Errors (e.g. OOM on a pathological PDF): this runs in an
            // unguarded viewModelScope.launch, so an escaped Error would crash.
            0
        }
    }

    /** Extracts [pageIndices] (0-based) into a single PDF, in ascending order. */
    suspend fun extract(
        source: PickedFile,
        pageIndices: List<Int>,
        outputBaseName: String
    ): CompressionResult = withContext(Dispatchers.IO) {
        if (pageIndices.isEmpty()) {
            throw CompressionException("Select at least one page to extract.")
        }
        val temp = PdfFiles.copyToCache(context, Uri.parse(source.uriString), "split_")
        var src: PDDocument? = null
        var out: PDDocument? = null
        try {
            src = PDDocument.load(temp, MemoryUsageSetting.setupTempFileOnly())
            val count = src.numberOfPages
            out = PDDocument()
            pageIndices.distinct().sorted().forEach { idx ->
                if (idx in 0 until count) {
                    PdfFiles.importPagePreserving(out, src.getPage(idx))
                }
            }
            if (out.numberOfPages == 0) {
                throw CompressionException("None of the selected pages are in this PDF.")
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
            throw CompressionException("This PDF is too large to split on this device.")
        } catch (e: CompressionException) {
            throw e
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            throw CompressionException("Couldn't split this PDF. It may be corrupted or password-protected.")
        } finally {
            runCatching { out?.close() }
            runCatching { src?.close() }
            temp.delete()
        }
    }

    /** Explodes every page into its own one-page PDF (one output file per page). */
    suspend fun splitEach(
        source: PickedFile,
        outputBaseName: String,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): CompressionResult = withContext(Dispatchers.IO) {
        val temp = PdfFiles.copyToCache(context, Uri.parse(source.uriString), "split_")
        var src: PDDocument? = null
        val items = ArrayList<OutputItem>()
        try {
            src = PDDocument.load(temp, MemoryUsageSetting.setupTempFileOnly())
            val count = src.numberOfPages
            if (count == 0) throw CompressionException("This PDF has no pages.")
            if (count > MAX_SPLIT_PAGES) {
                // One output file per page: a 20,000-page PDF would otherwise spew
                // 20,000 PDFs into Downloads. Use "extract a range" for huge docs.
                throw CompressionException(
                    "This PDF has $count pages. Splitting into more than $MAX_SPLIT_PAGES " +
                        "separate files isn't supported — extract a page range instead."
                )
            }

            items.ensureCapacity(count)
            for (i in 0 until count) {
                coroutineContext.ensureActive()
                val one = PDDocument()
                try {
                    PdfFiles.importPagePreserving(one, src.getPage(i))
                    val outName = storage.composeOutputName("${outputBaseName}_p${i + 1}", "pdf")
                    val saved = storage.writeOutput(outName, "application/pdf") { os -> one.save(os) }
                    items += OutputItem(
                        displayName = outName,
                        // Attribute the source size once so the results screen
                        // reports a sane input footprint for the whole job.
                        originalSize = if (i == 0) source.sizeBytes else 0L,
                        outputSize = saved.sizeBytes,
                        uri = saved.uri.toString(),
                        type = FileType.PDF
                    )
                } finally {
                    runCatching { one.close() }
                }
                onProgress(i + 1, count)
            }
            CompressionResult(items)
        } catch (oom: OutOfMemoryError) {
            throw CompressionException("This PDF is too large to split on this device.")
        } catch (e: CompressionException) {
            throw e
        } catch (c: CancellationException) {
            // Cancelled mid-run — remove the per-page PDFs already written.
            items.forEach { storage.deleteOutput(it.uri) }
            throw c
        } catch (e: Exception) {
            throw CompressionException("Couldn't split this PDF. It may be corrupted or password-protected.")
        } finally {
            runCatching { src?.close() }
            temp.delete()
        }
    }

    companion object {
        /** Max one-file-per-page outputs for split-each (storage / time safeguard). */
        private const val MAX_SPLIT_PAGES = 500
    }
}
