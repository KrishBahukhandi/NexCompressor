package com.nexcompress.app.data.processor

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.text.StaticLayout
import android.text.TextPaint
import com.nexcompress.app.domain.model.CompressionException
import com.nexcompress.app.domain.model.CompressionResult
import com.nexcompress.app.domain.model.FileType
import com.nexcompress.app.domain.model.OutputItem
import com.nexcompress.app.domain.model.PickedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * TXT → PDF. Reads a plain-text file and lays it out into a clean, paginated
 * A4 PDF using [StaticLayout] for word-wrapping. Pages break on line boundaries
 * so no line is ever clipped. Guarded against OOM and oversized inputs.
 */
class TxtToPdfConverter(
    private val context: Context,
    private val storage: FileStorageManager
) {

    suspend fun convert(
        input: PickedFile,
        outputBaseName: String,
        fontSizePt: Int
    ): CompressionResult = withContext(Dispatchers.IO) {
        if (input.sizeBytes > MAX_TEXT_BYTES) {
            throw CompressionException("This text file is too large to convert (over 5 MB).")
        }

        val uri = Uri.parse(input.uriString)
        val text = try {
            context.contentResolver.openInputStream(uri)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            } ?: throw CompressionException("Unable to open the selected text file.")
        } catch (oom: OutOfMemoryError) {
            throw CompressionException("This text file is too large to convert.")
        } catch (e: CompressionException) {
            throw e
        } catch (e: Exception) {
            throw CompressionException("Couldn't read the selected text file.")
        }

        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        if (normalized.isBlank()) {
            throw CompressionException("The text file is empty.")
        }

        val document = PdfDocument()
        try {
            paginate(document, normalized, fontSizePt.coerceIn(7, 24))

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
            throw CompressionException("This text file is too large to convert.")
        } catch (e: CompressionException) {
            throw e
        } catch (e: Exception) {
            throw CompressionException("Couldn't create the PDF from this text file.")
        } finally {
            runCatching { document.close() }
        }
    }

    private suspend fun paginate(document: PdfDocument, text: String, fontSizePt: Int) {
        val paint = TextPaint().apply {
            color = Color.BLACK
            textSize = fontSizePt.toFloat()
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            isAntiAlias = true
        }
        val contentWidth = PAGE_WIDTH - 2 * MARGIN
        val contentHeight = PAGE_HEIGHT - 2 * MARGIN

        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, paint, contentWidth)
            .setLineSpacing(0f, 1.15f)
            .setIncludePad(false)
            .build()

        val lineCount = layout.lineCount
        var startLine = 0
        var pageNumber = 1
        while (startLine < lineCount) {
            coroutineContext.ensureActive() // cooperative cancellation
            val pageTop = layout.getLineTop(startLine)

            // Pack as many whole lines as fit in the content height.
            var endLine = startLine
            while (endLine < lineCount && (layout.getLineBottom(endLine) - pageTop) <= contentHeight) {
                endLine++
            }
            if (endLine == startLine) endLine = startLine + 1 // a single over-tall line

            val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create()
            val page = document.startPage(pageInfo)
            val canvas = page.canvas
            canvas.drawColor(Color.WHITE)
            canvas.save()
            // Shift so [startLine] sits at the top margin, and clip to the content box
            // (clip prevents the off-page remainder of the layout from drawing).
            canvas.translate(MARGIN.toFloat(), (MARGIN - pageTop).toFloat())
            canvas.clipRect(
                0f,
                pageTop.toFloat(),
                contentWidth.toFloat(),
                (pageTop + contentHeight).toFloat()
            )
            layout.draw(canvas)
            canvas.restore()
            document.finishPage(page)

            startLine = endLine
            pageNumber++
        }
    }

    companion object {
        // A4 at 72 dpi (points), with a comfortable margin.
        private const val PAGE_WIDTH = 595
        private const val PAGE_HEIGHT = 842
        private const val MARGIN = 40
        private const val MAX_TEXT_BYTES = 5L * 1024 * 1024
    }
}
