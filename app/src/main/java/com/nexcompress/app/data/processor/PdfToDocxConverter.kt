package com.nexcompress.app.data.processor

import android.content.Context
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
import java.io.StringWriter
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * PDF → Word (.docx), fully on-device and text-focused.
 *
 * Extracts the document text in reading order (with per-line font sizes),
 * maps visibly larger lines to Word heading styles, and writes a clean,
 * valid .docx with a page break per source page. Layout, images and exact
 * fonts are not reproduced — the output is meant for editing the content.
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
                if (pages.sumOf { it.size } == 0) {
                    throw CompressionException(
                        "No selectable text found in this PDF — it looks scanned. " +
                            "OCR isn't supported yet."
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

    private fun extractLines(staged: java.io.File): List<List<Line>> {
        PDDocument.load(staged, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
            if (doc.isEncrypted) {
                throw CompressionException(
                    "This PDF is password-protected. Unlock it first via Protect PDF."
                )
            }
            val pages = mutableListOf<List<Line>>()
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
                    pages.add(current.toList())
                    current.clear()
                }
            }
            stripper.sortByPosition = true
            stripper.writeText(doc, StringWriter()) // drives extraction; output unused
            return pages
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
        pages.forEachIndexed { index, lines ->
            if (index > 0) {
                body.append("""<w:p><w:r><w:br w:type="page"/></w:r></w:p>""")
            }
            for (line in lines) {
                if (line.text.isEmpty()) {
                    body.append("<w:p/>")
                    continue
                }
                val style = styleFor(line)
                body.append("<w:p>")
                if (style != null) {
                    body.append("""<w:pPr><w:pStyle w:val="$style"/></w:pPr>""")
                }
                body.append("""<w:r><w:t xml:space="preserve">""")
                    .append(escapeXml(line.text))
                    .append("</w:t></w:r></w:p>")
            }
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
    }
}
