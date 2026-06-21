package com.nexcompress.app.data.processor

import android.content.Context
import android.net.Uri
import com.nexcompress.app.data.processor.OoxmlSupport.escapeXml
import com.nexcompress.app.data.processor.OoxmlSupport.putPart
import com.nexcompress.app.domain.model.CompressionException
import com.nexcompress.app.domain.model.CompressionResult
import com.nexcompress.app.domain.model.FileType
import com.nexcompress.app.domain.model.OutputItem
import com.nexcompress.app.domain.model.PickedFile
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File
import java.io.StringWriter
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * PDF → Word (.docx), fully on-device.
 *
 * For pages with an embedded text layer it extracts the text in reading order
 * (with per-line font sizes). For scanned / image-only pages it falls back to
 * on-device OCR ([OcrEngine]) so even scans become editable text. Visibly larger
 * lines map to Word heading styles; the result is a clean, valid .docx with a
 * page break per source page. Layout, images and exact fonts are not reproduced.
 */
class PdfToDocxConverter(
    private val context: Context,
    private val storage: FileStorageManager
) {

    private data class Line(val text: String, val sizePt: Float)

    suspend fun convert(input: PickedFile, outputBaseName: String): CompressionResult =
        withContext(Dispatchers.IO) {
            val staged = OoxmlSupport.stage(context, input.uriString, "pdf2docx_")
            try {
                val pages = extractLines(staged)
                if (pages.all { page -> page.none { it.text.isNotBlank() } }) {
                    throw CompressionException(
                        "We couldn't find any text in this PDF, even with OCR. " +
                            "The pages may be blank or too low-quality to read."
                    )
                }

                val outName = storage.composeOutputName(outputBaseName, "docx")
                val saved = storage.writeOutput(outName, DOCX_MIME) { os ->
                    writeDocx(ZipOutputStream(os), pages)
                }
                CompressionResult(
                    listOf(
                        OutputItem(
                            displayName = outName,
                            originalSize = saved.sizeBytes,
                            outputSize = saved.sizeBytes,
                            uri = saved.uri.toString(),
                            type = FileType.DOCUMENT
                        )
                    )
                )
            } catch (e: InvalidPasswordException) {
                throw CompressionException(
                    "This PDF is password-protected. Unlock it first via Protect PDF."
                )
            } catch (e: CompressionException) {
                throw e
            } catch (oom: OutOfMemoryError) {
                throw CompressionException("This PDF is too large to convert on this device.")
            } catch (e: Exception) {
                throw CompressionException("Couldn't convert this PDF. It may be corrupted.")
            } finally {
                staged.delete()
            }
        }

    // ------------------------------------------------------------------ //
    // Extraction                                                         //
    // ------------------------------------------------------------------ //

    private fun extractLines(staged: File): List<List<Line>> {
        PDDocument.load(staged, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
            if (doc.isEncrypted) {
                throw CompressionException(
                    "This PDF is password-protected. Unlock it first via Protect PDF."
                )
            }
            val pages = extractEmbeddedText(doc)

            // Any page with no embedded text layer is scanned/image-only → OCR it.
            val scannedPages = pages.indices.filter { idx ->
                pages[idx].none { it.text.isNotBlank() }
            }
            if (scannedPages.isNotEmpty()) {
                ocrPages(staged, doc, scannedPages, pages)
            }
            return pages
        }
    }

    /** Per-page lines from the embedded text layer (empty list for scanned pages). */
    private fun extractEmbeddedText(doc: PDDocument): MutableList<MutableList<Line>> {
        val pages = mutableListOf<MutableList<Line>>()
        val current = mutableListOf<Line>()
        val lineText = StringBuilder()
        var lineSizeSum = 0f
        var lineGlyphs = 0

        val stripper = object : PDFTextStripper() {
            fun flushLine() {
                val text = lineText.toString().trimEnd()
                if (text.isNotBlank()) {
                    val avg = if (lineGlyphs > 0) lineSizeSum / lineGlyphs else 0f
                    current.add(Line(text, avg))
                } else if (text.isEmpty() && current.isNotEmpty()) {
                    current.add(Line("", 0f)) // preserve vertical gaps as blanks
                }
                lineText.setLength(0); lineSizeSum = 0f; lineGlyphs = 0
            }

            override fun writeString(text: String, positions: List<TextPosition>) {
                lineText.append(text)
                for (pos in positions) {
                    val s = if (pos.fontSizeInPt > 0f) pos.fontSizeInPt else pos.yScale
                    lineSizeSum += s
                    lineGlyphs++
                }
            }

            override fun writeLineSeparator() {
                flushLine()
            }

            override fun endPage(page: com.tom_roush.pdfbox.pdmodel.PDPage) {
                flushLine()
                pages.add(current.toMutableList())
                current.clear()
            }
        }
        stripper.sortByPosition = true
        stripper.writeText(doc, StringWriter()) // drives extraction; output unused
        return pages
    }

    /**
     * Renders each [scannedPages] index and replaces its (empty) entry with
     * OCR'd lines. A line's font size is estimated from its bounding-box height
     * relative to the page so heading detection still applies to scans.
     */
    private fun ocrPages(
        staged: File,
        doc: PDDocument,
        scannedPages: List<Int>,
        pages: MutableList<MutableList<Line>>
    ) {
        var renderer: PdfPageRenderer? = null
        try {
            renderer = PdfPageRenderer(context, Uri.fromFile(staged))
            for (idx in scannedPages) {
                val bmp = renderer.renderPage(idx, OCR_RENDER_LONG_EDGE) ?: continue
                try {
                    val page = doc.getPage(idx)
                    val rotation = ((page.rotation % 360) + 360) % 360
                    val visualHeightPt =
                        if (rotation == 90 || rotation == 270) page.cropBox.width
                        else page.cropBox.height
                    val ocrLines = mutableListOf<Line>()
                    for (block in OcrEngine.recognizeBlocks(bmp)) {
                        for (ocr in block) {
                            val sizePt = if (bmp.height > 0 && ocr.heightPx > 0 && visualHeightPt > 0f) {
                                // Box height ≈ 1.3× the cap-height; scale toward point size.
                                (ocr.heightPx * visualHeightPt / bmp.height * 0.75f)
                                    .coerceIn(6f, 48f)
                            } else {
                                0f
                            }
                            ocrLines.add(Line(ocr.text, sizePt))
                        }
                        ocrLines.add(Line("", 0f)) // paragraph gap between blocks
                    }
                    if (ocrLines.any { it.text.isNotBlank() }) pages[idx] = ocrLines
                } finally {
                    bmp.recycle()
                }
            }
        } finally {
            runCatching { renderer?.close() }
        }
    }

    // ------------------------------------------------------------------ //
    // .docx generation                                                   //
    // ------------------------------------------------------------------ //

    private fun writeDocx(zip: ZipOutputStream, pages: List<List<Line>>) {
        // Body size = the most common rounded line size across the document.
        val sizeHistogram = mutableMapOf<Int, Int>()
        pages.flatten().forEach { line ->
            if (line.text.isNotBlank() && line.sizePt > 1f) {
                val key = (line.sizePt * 2).roundToInt() // half-point buckets
                sizeHistogram[key] = (sizeHistogram[key] ?: 0) + line.text.length
            }
        }
        val bodyHalfPt = sizeHistogram.maxByOrNull { it.value }?.key ?: 22

        fun styleFor(line: Line): String? {
            if (line.text.length > 120) return null
            val halfPt = (line.sizePt * 2).roundToInt()
            return when {
                halfPt >= (bodyHalfPt * 1.5f).roundToInt() && halfPt > bodyHalfPt -> "Heading1"
                halfPt >= (bodyHalfPt * 1.2f).roundToInt() && halfPt > bodyHalfPt -> "Heading2"
                else -> null
            }
        }

        val body = StringBuilder()

        fun appendParagraph(text: String, style: String?) {
            body.append("<w:p>")
            if (style != null) body.append("""<w:pPr><w:pStyle w:val="$style"/></w:pPr>""")
            body.append("""<w:r><w:t xml:space="preserve">""")
                .append(escapeXml(text))
                .append("</w:t></w:r></w:p>")
        }

        pages.forEachIndexed { index, lines ->
            if (index > 0) {
                body.append("""<w:p><w:r><w:br w:type="page"/></w:r></w:p>""")
            }
            // The widest body line on the page ≈ a full (wrapped) line. A body
            // line that reaches near that width and doesn't end a clause is a
            // wrap continuation, so we rejoin it with the following line(s) into a
            // single reflowable paragraph — rather than emitting one stranded
            // paragraph per visual line (which makes the .docx painful to edit).
            // We deliberately under-merge (keep short lines, list items, and
            // clause-ending lines separate) since that degrades gracefully.
            val bodyMax = lines
                .filter { it.text.isNotBlank() && styleFor(it) == null }
                .maxOfOrNull { it.text.length } ?: 0

            val para = StringBuilder()
            fun flushPara() {
                if (para.isNotEmpty()) {
                    appendParagraph(para.toString(), null)
                    para.setLength(0)
                }
            }

            lines.forEachIndexed { li, line ->
                if (line.text.isEmpty()) {
                    flushPara()
                    body.append("<w:p/>")
                    return@forEachIndexed
                }
                val style = styleFor(line)
                if (style != null) {
                    flushPara()
                    appendParagraph(line.text, style)
                    return@forEachIndexed
                }
                if (para.isNotEmpty()) para.append(' ')
                para.append(line.text)

                val isFullWidth = bodyMax > 0 && line.text.length >= (bodyMax * WRAP_FULLNESS).toInt()
                val endsClause = line.text.last() in CLAUSE_ENDERS
                val nextIsBody = li + 1 < lines.size &&
                    lines[li + 1].text.isNotBlank() && styleFor(lines[li + 1]) == null
                if (!(isFullWidth && !endsClause && nextIsBody)) flushPara()
            }
            flushPara()
        }

        zip.use { z ->
            z.putPart(
                "[Content_Types].xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
<Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
</Types>"""
            )
            z.putPart(
                "_rels/.rels",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>"""
            )
            z.putPart(
                "word/_rels/document.xml.rels",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>"""
            )
            z.putPart(
                "word/styles.xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
<w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/>
<w:rPr><w:sz w:val="$bodyHalfPt"/></w:rPr></w:style>
<w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:basedOn w:val="Normal"/>
<w:pPr><w:spacing w:before="240" w:after="120"/><w:outlineLvl w:val="0"/></w:pPr>
<w:rPr><w:b/><w:sz w:val="${(bodyHalfPt * 1.6f).roundToInt()}"/></w:rPr></w:style>
<w:style w:type="paragraph" w:styleId="Heading2"><w:name w:val="heading 2"/><w:basedOn w:val="Normal"/>
<w:pPr><w:spacing w:before="200" w:after="100"/><w:outlineLvl w:val="1"/></w:pPr>
<w:rPr><w:b/><w:sz w:val="${(bodyHalfPt * 1.3f).roundToInt()}"/></w:rPr></w:style>
</w:styles>"""
            )
            z.putPart(
                "word/document.xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
<w:body>$body<w:sectPr><w:pgSz w:w="12240" w:h="15840"/>
<w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/></w:sectPr></w:body>
</w:document>"""
            )
        }
    }

    companion object {
        private const val DOCX_MIME =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

        /** Render resolution (longest edge, px) for OCR'ing a scanned page. */
        private const val OCR_RENDER_LONG_EDGE = 2200

        /** A line at least this fraction of the page's widest line is "full" (wrapped). */
        private const val WRAP_FULLNESS = 0.66f

        /** Trailing chars that end a clause, so the next line starts a new paragraph. */
        private const val CLAUSE_ENDERS = ".?!:"
    }
}
