package com.nexcompress.app.data.processor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import com.nexcompress.app.data.processor.OoxmlSupport.attr
import com.nexcompress.app.domain.model.CompressionException
import com.nexcompress.app.domain.model.CompressionResult
import com.nexcompress.app.domain.model.FileType
import com.nexcompress.app.domain.model.OutputItem
import com.nexcompress.app.domain.model.PickedFile
import java.io.File
import java.util.zip.ZipFile
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser

/**
 * Word (.docx) → PDF, fully on-device.
 *
 * Parses word/document.xml directly (paragraphs, runs with bold/italic/
 * underline/size/color, headings, bullets, alignment, basic tables, inline
 * images) and lays the content out with [StaticLayout] into a paginated PDF
 * using the document's own page size and margins. Content-faithful by design;
 * complex layout (columns, text boxes, floating objects) is simplified.
 * Legacy binary .doc is not supported.
 */
class DocxToPdfConverter(
    private val context: Context,
    private val storage: FileStorageManager
) {

    // ---- Parsed document model ----
    private data class Run(
        val text: String,
        val bold: Boolean,
        val italic: Boolean,
        val underline: Boolean,
        val sizePt: Float?,
        val color: Int?
    )

    private sealed interface Block
    private data class Para(
        val runs: List<Run>,
        val heading: Int, // 0 = body, 1..3
        val bullet: Boolean,
        val align: Layout.Alignment
    ) : Block

    private data class Image(val zipEntry: String) : Block
    private data class Table(val rows: List<List<List<Para>>>, val gridTwips: List<Int>) : Block

    private data class PageSpec(
        val width: Float,
        val height: Float,
        val margins: RectF
    )

    suspend fun convert(input: PickedFile, outputBaseName: String): CompressionResult =
        withContext(Dispatchers.IO) {
            val staged = OoxmlSupport.stage(context, input.uriString, "docx_")
            val document = PdfDocument()
            try {
                val (blocks, spec, images) = parsePackage(staged)
                if (blocks.isEmpty()) {
                    throw CompressionException("This Word document appears to be empty.")
                }
                render(document, blocks, spec, images)

                val outName = storage.composeOutputName(outputBaseName, "pdf")
                val saved = storage.writeOutput(outName, "application/pdf") { os ->
                    document.writeTo(os)
                }
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
            } catch (e: CompressionException) {
                throw e
            } catch (oom: OutOfMemoryError) {
                throw CompressionException("This document is too large to convert on this device.")
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                throw CompressionException(
                    "Couldn't read this file as a Word document. " +
                        "Legacy .doc files aren't supported — save it as .docx and try again."
                )
            } finally {
                runCatching { document.close() }
                staged.delete()
            }
        }

    // ------------------------------------------------------------------ //
    // Parsing                                                            //
    // ------------------------------------------------------------------ //

    private class ImageStore(private val file: File) {
        fun decode(entryName: String, maxEdgePx: Int): Bitmap? = try {
            ZipFile(file).use { zip ->
                val bytes = OoxmlSupport.readPart(zip, entryName) ?: return null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                var edge = max(bounds.outWidth, bounds.outHeight)
                while (edge / 2 >= maxEdgePx) {
                    edge /= 2
                    sample *= 2
                }
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            }
        } catch (e: Exception) {
            null
        } catch (oom: OutOfMemoryError) {
            null
        }
    }

    private fun parsePackage(staged: File): Triple<List<Block>, PageSpec, ImageStore> {
        ZipFile(staged).use { zip ->
            val docXml = OoxmlSupport.requirePart(zip, "word/document.xml", "Word (.docx)")
            val rels = OoxmlSupport.parseRelationships(
                OoxmlSupport.readPart(zip, "word/_rels/document.xml.rels"), "word"
            )
            val blocks = mutableListOf<Block>()
            var spec = PageSpec(612f, 792f, RectF(72f, 72f, 72f, 72f)) // Letter, 1" margins

            val p = OoxmlSupport.parser(docXml)
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType != XmlPullParser.START_TAG) continue
                when (p.name) {
                    "p" -> parseParagraph(p, rels, blocks)
                    "tbl" -> blocks.add(parseTable(p, rels))
                    "pgSz" -> {
                        val w = attr(p, "w")?.toFloatOrNull()?.div(20f)
                        val h = attr(p, "h")?.toFloatOrNull()?.div(20f)
                        if (w != null && h != null && w > 100 && h > 100) {
                            spec = spec.copy(width = w, height = h)
                        }
                    }
                    "pgMar" -> {
                        fun m(name: String, def: Float) =
                            (attr(p, name)?.toFloatOrNull()?.div(20f) ?: def).coerceIn(12f, 200f)
                        spec = spec.copy(
                            margins = RectF(m("left", 72f), m("top", 72f), m("right", 72f), m("bottom", 72f))
                        )
                    }
                }
            }
            return Triple(blocks, spec, ImageStore(staged))
        }
    }

    /** Parses one w:p; emits a Para and any inline images as separate blocks. */
    private fun parseParagraph(
        p: XmlPullParser,
        rels: Map<String, String>,
        out: MutableList<Block>
    ) {
        val runs = mutableListOf<Run>()
        val images = mutableListOf<String>()
        var heading = 0
        var bullet = false
        var align = Layout.Alignment.ALIGN_NORMAL

        var bold = false
        var italic = false
        var underline = false
        var sizePt: Float? = null
        var color: Int? = null
        var inRunProps = false

        val startDepth = p.depth
        while (!(p.next() == XmlPullParser.END_TAG && p.depth == startDepth)) {
            if (p.eventType == XmlPullParser.END_DOCUMENT) break
            if (p.eventType == XmlPullParser.END_TAG) {
                if (p.name == "rPr") inRunProps = false
                continue
            }
            if (p.eventType != XmlPullParser.START_TAG) continue
            when (p.name) {
                "pStyle" -> heading = docxHeadingLevelOf(attr(p, "val")) ?: heading
                "numPr" -> bullet = true
                "jc" -> align = when (attr(p, "val")) {
                    "center" -> Layout.Alignment.ALIGN_CENTER
                    "right", "end" -> Layout.Alignment.ALIGN_OPPOSITE
                    else -> Layout.Alignment.ALIGN_NORMAL
                }
                "r" -> { // new run: reset direct formatting
                    bold = false; italic = false; underline = false; sizePt = null; color = null
                }
                "rPr" -> inRunProps = true
                "b" -> if (inRunProps) bold = attr(p, "val") !in listOf("false", "0", "none")
                "i" -> if (inRunProps) italic = attr(p, "val") !in listOf("false", "0", "none")
                "u" -> if (inRunProps) underline = attr(p, "val") != "none"
                "sz" -> if (inRunProps) sizePt = attr(p, "val")?.toFloatOrNull()?.div(2f)
                "color" -> if (inRunProps) {
                    color = attr(p, "val")
                        ?.takeIf { it.length == 6 && it != "auto" }
                        ?.toLongOrNull(16)?.let { (0xFF000000L or it).toInt() }
                }
                "t" -> {
                    val text = p.nextText()
                    if (text.isNotEmpty()) {
                        runs.add(Run(text, bold, italic, underline, sizePt, color))
                    }
                }
                "br", "cr" -> runs.add(Run("\n", bold, italic, underline, sizePt, color))
                "tab" -> runs.add(Run("\t", bold, italic, underline, sizePt, color))
                "blip" -> attr(p, "embed")?.let { rId ->
                    rels[rId]?.let { images.add(it) }
                }
            }
        }

        out.add(Para(runs, heading, bullet, align))
        images.forEach { out.add(Image(it)) }
    }

    private fun parseTable(p: XmlPullParser, rels: Map<String, String>): Table {
        val grid = mutableListOf<Int>()
        val rows = mutableListOf<List<List<Para>>>()
        var currentRow: MutableList<List<Para>>? = null
        var currentCell: MutableList<Block>? = null

        val startDepth = p.depth
        while (!(p.next() == XmlPullParser.END_TAG && p.depth == startDepth)) {
            if (p.eventType == XmlPullParser.END_DOCUMENT) break
            if (p.eventType == XmlPullParser.END_TAG) {
                when (p.name) {
                    "tr" -> currentRow?.let { rows.add(it) }.also { currentRow = null }
                    "tc" -> {
                        val paras = currentCell.orEmpty().flatMap { block ->
                            when (block) {
                                is Para -> listOf(block)
                                is Table -> block.rows.flatten().flatten() // flatten nested tables
                                else -> emptyList()
                            }
                        }
                        currentRow?.add(paras)
                        currentCell = null
                    }
                }
                continue
            }
            if (p.eventType != XmlPullParser.START_TAG) continue
            when (p.name) {
                "gridCol" -> attr(p, "w")?.toIntOrNull()?.let { grid.add(it) }
                "tr" -> currentRow = mutableListOf()
                "tc" -> currentCell = mutableListOf()
                "p" -> currentCell?.let { parseParagraph(p, rels, it) }
                "tbl" -> currentCell?.add(parseTable(p, rels))
            }
        }
        return Table(rows, grid)
    }

    // ------------------------------------------------------------------ //
    // Rendering                                                          //
    // ------------------------------------------------------------------ //

    /** Greedy top-down paginator over a PdfDocument. */
    private class PageWriter(
        private val doc: PdfDocument,
        private val spec: PageSpec
    ) {
        private var page: PdfDocument.Page? = null
        var y = 0f; private set
        val contentWidth = spec.width - spec.margins.left - spec.margins.right
        val contentHeight = spec.height - spec.margins.top - spec.margins.bottom
        private val bottom = spec.height - spec.margins.bottom
        val left get() = spec.margins.left
        val available get() = bottom - y

        fun canvas(): Canvas {
            if (page == null) newPage()
            return page!!.canvas
        }

        fun newPage() {
            finishPage()
            val info = PdfDocument.PageInfo
                .Builder(ceil(spec.width).toInt(), ceil(spec.height).toInt(), 1)
                .create()
            page = doc.startPage(info).also { it.canvas.drawColor(Color.WHITE) }
            y = spec.margins.top
        }

        fun advance(by: Float) {
            y += by
        }

        fun finishPage() {
            page?.let { doc.finishPage(it) }
            page = null
        }
    }

    private suspend fun render(
        doc: PdfDocument,
        blocks: List<Block>,
        spec: PageSpec,
        images: ImageStore
    ) {
        val writer = PageWriter(doc, spec)
        writer.newPage()
        for (block in blocks) {
            coroutineContext.ensureActive()
            when (block) {
                is Para -> drawParagraph(writer, block)
                is Image -> drawImage(writer, block, images)
                is Table -> drawTable(writer, block)
            }
        }
        writer.finishPage()
    }

    private fun paintFor(heading: Int): TextPaint = TextPaint().apply {
        isAntiAlias = true
        color = Color.BLACK
        textSize = when (heading) {
            1 -> 20f; 2 -> 15f; 3 -> 12.5f; else -> 11f
        }
        typeface = if (heading > 0) {
            Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        } else {
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
    }

    private fun spannableOf(para: Para): SpannableStringBuilder {
        val ssb = SpannableStringBuilder()
        if (para.bullet) ssb.append("•  ")
        for (run in para.runs) {
            val start = ssb.length
            ssb.append(run.text)
            val end = ssb.length
            if (end == start) continue
            fun span(what: Any) = ssb.setSpan(what, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            when {
                run.bold && run.italic -> span(StyleSpan(Typeface.BOLD_ITALIC))
                run.bold -> span(StyleSpan(Typeface.BOLD))
                run.italic -> span(StyleSpan(Typeface.ITALIC))
            }
            if (run.underline) span(UnderlineSpan())
            run.sizePt?.let { span(AbsoluteSizeSpan(it.roundToInt())) }
            run.color?.let { span(ForegroundColorSpan(it)) }
        }
        return ssb
    }

    private fun layoutOf(para: Para, width: Int): StaticLayout {
        val text = spannableOf(para)
        return StaticLayout.Builder
            .obtain(text, 0, text.length, paintFor(para.heading), width.coerceAtLeast(1))
            .setAlignment(para.align)
            .setLineSpacing(0f, 1.18f)
            .setIncludePad(false)
            .build()
    }

    private fun drawParagraph(writer: PageWriter, para: Para) {
        val indent = if (para.bullet) 10f else 0f
        val spacingBefore = if (para.heading > 0) 10f else 2f
        val layout = layoutOf(para, (writer.contentWidth - indent).toInt())

        if (layout.text.isEmpty()) { // empty paragraph = vertical gap
            writer.advance(8f)
            return
        }
        writer.advance(spacingBefore.coerceAtMost(writer.available))

        var line = 0
        while (line < layout.lineCount) {
            if (writer.available < layout.getLineBottom(line) - layout.getLineTop(line)) {
                writer.newPage()
            }
            val top = layout.getLineTop(line)
            var endLine = line
            while (endLine < layout.lineCount &&
                layout.getLineBottom(endLine) - top <= writer.available
            ) {
                endLine++
            }
            if (endLine == line) endLine = line + 1
            val chunkBottom = layout.getLineBottom(endLine - 1)

            val canvas = writer.canvas()
            canvas.save()
            canvas.translate(writer.left + indent, writer.y - top)
            canvas.clipRect(
                -indent, top.toFloat(),
                writer.contentWidth, chunkBottom.toFloat()
            )
            layout.draw(canvas)
            canvas.restore()
            writer.advance((chunkBottom - top).toFloat())
            line = endLine
        }
        writer.advance(4f)
    }

    private fun drawImage(writer: PageWriter, image: Image, images: ImageStore) {
        val maxW = writer.contentWidth
        val bmp = images.decode(image.zipEntry, (maxW * 2).toInt().coerceAtMost(2200)) ?: return
        try {
            var w = maxW
            var h = w * bmp.height / bmp.width.coerceAtLeast(1)
            // Move to a fresh page when the image would fit much better there.
            if (h > writer.available && writer.available < writer.contentHeight * 0.7f) {
                writer.newPage()
            }
            val cap = (writer.available - 12f).coerceAtLeast(48f)
            if (h > cap) {
                h = cap
                w = h * bmp.width / bmp.height.coerceAtLeast(1)
            }
            writer.advance(6f.coerceAtMost(writer.available))
            val canvas = writer.canvas()
            val l = writer.left + (writer.contentWidth - w) / 2f
            canvas.drawBitmap(bmp, null, RectF(l, writer.y, l + w, writer.y + h), null)
            writer.advance(h + 6f)
        } finally {
            bmp.recycle()
        }
    }

    private fun drawTable(writer: PageWriter, table: Table) {
        if (table.rows.isEmpty()) return
        val colCount = max(table.gridTwips.size, table.rows.maxOf { it.size }).coerceAtLeast(1)
        val widths = FloatArray(colCount)
        if (table.gridTwips.size == colCount && table.gridTwips.sum() > 0) {
            val total = table.gridTwips.sum().toFloat()
            for (i in 0 until colCount) {
                widths[i] = writer.contentWidth * (table.gridTwips[i] / total)
            }
        } else {
            for (i in 0 until colCount) widths[i] = writer.contentWidth / colCount
        }

        val border = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.75f
            color = Color.rgb(187, 187, 187)
        }
        val cellPad = 4f

        writer.advance(6f.coerceAtMost(writer.available))
        for (row in table.rows) {
            // Lay out every cell to know the row height.
            val layouts = row.take(colCount).mapIndexed { i, paras ->
                paras.map { para ->
                    layoutOf(
                        para.copy(heading = 0),
                        (widths[i] - 2 * cellPad).toInt().coerceAtLeast(8)
                    )
                }
            }
            val rowHeight = (layouts.maxOfOrNull { cell ->
                cell.sumOf { it.height } + 2 * cellPad
            } ?: 0f).coerceAtLeast(16f)

            if (rowHeight > writer.available) writer.newPage()
            val canvas = writer.canvas()
            val drawHeight = rowHeight.coerceAtMost(writer.available)
            var x = writer.left
            for (i in 0 until colCount) {
                canvas.drawRect(x, writer.y, x + widths[i], writer.y + drawHeight, border)
                val cell = layouts.getOrNull(i)
                if (cell != null) {
                    canvas.save()
                    canvas.clipRect(
                        x + cellPad, writer.y,
                        x + widths[i] - cellPad, writer.y + drawHeight
                    )
                    canvas.translate(x + cellPad, writer.y + cellPad)
                    for (layout in cell) {
                        layout.draw(canvas)
                        canvas.translate(0f, layout.height.toFloat())
                    }
                    canvas.restore()
                }
                x += widths[i]
            }
            writer.advance(drawHeight)
        }
        writer.advance(6f)
    }
}

/**
 * Maps a Word paragraph style id to a render heading level (1..3), or null for
 * body text. Matched on the style's numeric heading level rather than a loose
 * substring, so "Subtitle" stays body and "Heading10" does not masquerade as
 * Heading 1. Exposed (internal) so it can be unit-tested directly.
 */
internal fun docxHeadingLevelOf(styleVal: String?): Int? {
    val v = styleVal?.lowercase() ?: return null
    if (v == "title") return 1
    // Word uses "Heading1" / "heading 1"; capture the level digit after "heading".
    val level = Regex("heading\\s*(\\d+)").find(v)?.groupValues?.get(1)?.toIntOrNull()
    return when {
        level == null || level < 1 -> null
        level == 1 -> 1
        level == 2 -> 2
        else -> 3 // level 3+ (incl. non-standard Heading10) → generic heading, never H1
    }
}
