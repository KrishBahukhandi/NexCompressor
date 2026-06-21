package com.nexcompress.app.data.processor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.TextPaint
import android.text.TextUtils
import com.nexcompress.app.data.processor.OoxmlSupport.attr
import com.nexcompress.app.domain.model.CompressionException
import com.nexcompress.app.domain.model.CompressionResult
import com.nexcompress.app.domain.model.FileType
import com.nexcompress.app.domain.model.OutputItem
import com.nexcompress.app.domain.model.PickedFile
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.zip.ZipFile
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser

/**
 * Excel (.xlsx) → PDF, fully on-device.
 *
 * Renders each worksheet as a paginated grid. Formula cells show their cached
 * values (no formula engine needed), shared/inline strings are resolved, and
 * date-formatted serial numbers are printed as dates. Wide sheets are split
 * into column groups across pages (the first row repeats as a header). Charts
 * and conditional formatting are not reproduced; legacy .xls is not supported.
 */
class XlsxToPdfConverter(
    private val context: Context,
    private val storage: FileStorageManager
) {

    private data class Cell(val text: String, val numeric: Boolean)
    private data class Sheet(
        val name: String,
        val rows: Map<Int, Map<Int, Cell>>, // rowIdx -> colIdx -> cell (0-based)
        val colWidths: Map<Int, Float>, // declared widths (pt)
        val maxCol: Int,
        val maxRow: Int,
        val truncatedRows: Int
    )

    suspend fun convert(input: PickedFile, outputBaseName: String): CompressionResult =
        withContext(Dispatchers.IO) {
            val staged = OoxmlSupport.stage(context, input.uriString, "xlsx_")
            val document = PdfDocument()
            try {
                val sheets = parseWorkbook(staged)
                if (sheets.isEmpty() || sheets.all { it.rows.isEmpty() }) {
                    throw CompressionException("This workbook has no readable cell data.")
                }
                render(document, sheets)

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
                throw CompressionException("This workbook is too large to convert on this device.")
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                throw CompressionException(
                    "Couldn't read this file as an Excel workbook. " +
                        "Legacy .xls files aren't supported — save it as .xlsx and try again."
                )
            } finally {
                runCatching { document.close() }
                staged.delete()
            }
        }

    // ------------------------------------------------------------------ //
    // Parsing                                                            //
    // ------------------------------------------------------------------ //

    private fun parseWorkbook(staged: java.io.File): List<Sheet> {
        ZipFile(staged).use { zip ->
            val workbook = OoxmlSupport.requirePart(zip, "xl/workbook.xml", "Excel (.xlsx)")
            val rels = OoxmlSupport.parseRelationships(
                OoxmlSupport.readPart(zip, "xl/_rels/workbook.xml.rels"), "xl"
            )
            val shared = parseSharedStrings(OoxmlSupport.readPart(zip, "xl/sharedStrings.xml"))
            val dateStyles = parseDateStyles(OoxmlSupport.readPart(zip, "xl/styles.xml"))

            data class Ref(val name: String, val rId: String)
            val refs = mutableListOf<Ref>()
            val wp = OoxmlSupport.parser(workbook)
            while (wp.next() != XmlPullParser.END_DOCUMENT) {
                if (wp.eventType == XmlPullParser.START_TAG && wp.name == "sheet") {
                    val name = attr(wp, "name") ?: "Sheet"
                    val rId = attr(wp, "id") ?: continue // r:id
                    refs.add(Ref(name, rId))
                }
            }

            return refs.mapNotNull { ref ->
                val path = rels[ref.rId] ?: return@mapNotNull null
                val bytes = OoxmlSupport.readPart(zip, path) ?: return@mapNotNull null
                parseSheet(ref.name, bytes, shared, dateStyles)
            }
        }
    }

    /** sharedStrings.xml: si items; rich-text runs are concatenated. */
    private fun parseSharedStrings(bytes: ByteArray?): List<String> {
        bytes ?: return emptyList()
        val strings = mutableListOf<String>()
        val sb = StringBuilder()
        var inSi = false
        val p = OoxmlSupport.parser(bytes)
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when {
                p.eventType == XmlPullParser.START_TAG && p.name == "si" -> {
                    inSi = true; sb.setLength(0)
                }
                p.eventType == XmlPullParser.START_TAG && p.name == "t" && inSi ->
                    sb.append(p.nextText())
                p.eventType == XmlPullParser.END_TAG && p.name == "si" -> {
                    inSi = false; strings.add(sb.toString())
                }
            }
        }
        return strings
    }

    /** Style indexes (cellXfs order) whose number format looks like a date. */
    private fun parseDateStyles(bytes: ByteArray?): Set<Int> {
        bytes ?: return emptySet()
        val dateFmtIds = mutableSetOf(14, 15, 16, 17, 22, 45, 46, 47)
        val styles = mutableSetOf<Int>()
        var inCellXfs = false
        var xfIndex = 0
        val p = OoxmlSupport.parser(bytes)
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG) {
                when (p.name) {
                    "numFmt" -> {
                        val id = attr(p, "numFmtId")?.toIntOrNull()
                        val code = attr(p, "formatCode")?.lowercase().orEmpty()
                        if (id != null &&
                            (code.contains("yy") || code.contains("dd") || code.contains("h:mm"))
                        ) {
                            dateFmtIds.add(id)
                        }
                    }
                    "cellXfs" -> { inCellXfs = true; xfIndex = 0 }
                    "xf" -> if (inCellXfs) {
                        val fmt = attr(p, "numFmtId")?.toIntOrNull() ?: 0
                        if (fmt in dateFmtIds) styles.add(xfIndex)
                        xfIndex++
                    }
                }
            } else if (p.eventType == XmlPullParser.END_TAG && p.name == "cellXfs") {
                inCellXfs = false
            }
        }
        return styles
    }

    private fun parseSheet(
        name: String,
        bytes: ByteArray,
        shared: List<String>,
        dateStyles: Set<Int>
    ): Sheet {
        val rows = sortedMapOf<Int, MutableMap<Int, Cell>>()
        val colWidths = mutableMapOf<Int, Float>()
        var maxCol = 0
        var maxRow = 0
        var truncated = 0

        var rowIdx = -1
        var colIdx = -1
        var cellType = ""
        var cellStyle = 0
        var inInline = false
        val value = StringBuilder()

        fun commitCell() {
            if (rowIdx < 0 || colIdx < 0) return
            if (rowIdx >= MAX_ROWS) { truncated++; return }
            val raw = value.toString()
            val cell = when (cellType) {
                "s" -> Cell(shared.getOrElse(raw.trim().toIntOrNull() ?: -1) { "" }, numeric = false)
                "b" -> Cell(if (raw.trim() == "1") "TRUE" else "FALSE", numeric = false)
                "e" -> Cell(raw, numeric = false)
                "str", "inlineStr" -> Cell(raw, numeric = false)
                else -> { // number (possibly date-formatted)
                    val d = raw.trim().toDoubleOrNull()
                    when {
                        d == null -> Cell(raw, numeric = false)
                        cellStyle in dateStyles && d > 1.0 -> Cell(formatSerialDate(d), numeric = false)
                        else -> Cell(formatNumber(d, raw.trim()), numeric = true)
                    }
                }
            }
            if (cell.text.isNotEmpty()) {
                rows.getOrPut(rowIdx) { mutableMapOf() }[colIdx] = cell
                maxCol = max(maxCol, colIdx)
                maxRow = max(maxRow, rowIdx)
            }
        }

        val p = OoxmlSupport.parser(bytes)
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "col" -> {
                        val min = attr(p, "min")?.toIntOrNull() ?: continue
                        val max0 = attr(p, "max")?.toIntOrNull() ?: min
                        val w = attr(p, "width")?.toFloatOrNull() ?: continue
                        val pt = (w * 5.25f).coerceIn(22f, 220f)
                        for (c in min..max0) colWidths[c - 1] = pt
                    }
                    "row" -> rowIdx = (attr(p, "r")?.toIntOrNull() ?: (rowIdx + 2)) - 1
                    "c" -> {
                        colIdx = attr(p, "r")?.let { OoxmlSupport.columnIndexOf(it) }
                            ?: (colIdx + 1)
                        cellType = attr(p, "t").orEmpty()
                        cellStyle = attr(p, "s")?.toIntOrNull() ?: 0
                        value.setLength(0)
                    }
                    "v" -> value.append(p.nextText())
                    "is" -> inInline = true
                    "t" -> if (inInline) value.append(p.nextText())
                }
                XmlPullParser.END_TAG -> when (p.name) {
                    "c" -> commitCell()
                    "is" -> inInline = false
                }
            }
        }
        return Sheet(name, rows, colWidths, maxCol, maxRow, truncated)
    }

    private fun formatNumber(d: Double, raw: String): String = try {
        BigDecimal(raw).stripTrailingZeros().toPlainString()
    } catch (e: Exception) {
        if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
    }

    /** Excel serial date (1900 system) → ISO date. */
    private fun formatSerialDate(serial: Double): String = try {
        val days = serial.toLong() - 25569L // to unix epoch days
        LocalDate.ofEpochDay(days).format(DateTimeFormatter.ISO_LOCAL_DATE)
    } catch (e: Exception) {
        serial.toString()
    }

    // ------------------------------------------------------------------ //
    // Rendering                                                          //
    // ------------------------------------------------------------------ //

    private suspend fun render(document: PdfDocument, sheets: List<Sheet>) {
        val textPaint = TextPaint().apply {
            isAntiAlias = true; color = Color.BLACK; textSize = 8.5f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
        val titlePaint = TextPaint().apply {
            isAntiAlias = true; color = Color.BLACK; textSize = 13f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val grid = Paint().apply {
            style = Paint.Style.STROKE; strokeWidth = 0.6f; color = Color.rgb(200, 200, 200)
        }
        val headerFill = Paint().apply {
            style = Paint.Style.FILL; color = Color.rgb(240, 242, 245)
        }

        // Landscape when any sheet is wide.
        val probeWidths = sheets.associateWith { columnWidths(it, textPaint) }
        val landscape = probeWidths.values.any { it.sum() > 612f - 2 * MARGIN }
        val pageW = if (landscape) 792 else 612
        val pageH = if (landscape) 612 else 792
        val contentW = pageW - 2 * MARGIN
        val bottom = pageH - MARGIN

        var pageNo = 0
        var page: PdfDocument.Page? = null
        var y = 0f

        fun newPage(): Canvas {
            page?.let { document.finishPage(it) }
            pageNo++
            val info = PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create()
            page = document.startPage(info)
            page!!.canvas.drawColor(Color.WHITE)
            y = MARGIN.toFloat()
            return page!!.canvas
        }

        for (sheet in sheets) {
            coroutineContext.ensureActive()
            val widths = probeWidths.getValue(sheet)
            // Split columns into groups that fit the content width.
            val chunks = mutableListOf<IntRange>()
            var start = 0
            while (start <= sheet.maxCol) {
                var endExclusive = start
                var acc = 0f
                while (endExclusive <= sheet.maxCol &&
                    (acc + widths[endExclusive] <= contentW || endExclusive == start)
                ) {
                    acc += widths[endExclusive]
                    endExclusive++
                }
                chunks.add(start until endExclusive)
                start = endExclusive
            }

            val headerCells = sheet.rows[0]
            for ((chunkIdx, chunk) in chunks.withIndex()) {
                coroutineContext.ensureActive()
                var canvas = newPage()
                val suffix = if (chunks.size > 1) {
                    "  (columns ${columnName(chunk.first)}–${columnName(chunk.last)})"
                } else ""
                canvas.drawText("Sheet: ${sheet.name}$suffix", MARGIN.toFloat(), y + 13f, titlePaint)
                y += 24f

                fun drawRow(rowIdx: Int, cells: Map<Int, Cell>?, isHeader: Boolean) {
                    if (y + ROW_H > bottom) {
                        canvas = newPage()
                    }
                    var x = MARGIN.toFloat()
                    if (isHeader) {
                        val w = chunk.sumOf { widths[it].toDouble() }.toFloat()
                        canvas.drawRect(x, y, x + w, y + ROW_H, headerFill)
                    }
                    for (c in chunk) {
                        val w = widths[c]
                        canvas.drawRect(x, y, x + w, y + ROW_H, grid)
                        val cell = cells?.get(c)
                        if (cell != null) {
                            val avail = w - 2 * CELL_PAD
                            val txt = TextUtils.ellipsize(cell.text, textPaint, avail, TextUtils.TruncateAt.END)
                            val tx = if (cell.numeric) {
                                x + w - CELL_PAD - textPaint.measureText(txt, 0, txt.length)
                            } else {
                                x + CELL_PAD
                            }
                            canvas.drawText(txt, 0, txt.length, tx, y + ROW_H - 4.5f, textPaint)
                        }
                        x += w
                    }
                    y += ROW_H
                }

                // Repeat row 1 as a header band on every chunk.
                if (headerCells != null) drawRow(0, headerCells, isHeader = true)
                for (r in (if (headerCells != null) 1 else 0)..sheet.maxRow) {
                    if (r % 64 == 0) coroutineContext.ensureActive()
                    val cells = sheet.rows[r]
                    // Keep small gaps visible but collapse long empty stretches.
                    if (cells == null && rowGapSkippable(sheet, r)) continue
                    if (y + ROW_H > bottom) {
                        canvas = newPage()
                        if (headerCells != null) drawRow(0, headerCells, isHeader = true)
                    }
                    drawRow(r, cells, isHeader = false)
                }
                if (sheet.truncatedRows > 0 && chunkIdx == chunks.size - 1) {
                    y += 14f
                    canvas.drawText(
                        "… ${sheet.truncatedRows} more rows not shown",
                        MARGIN.toFloat(), y, textPaint
                    )
                }
            }
        }
        page?.let { document.finishPage(it) }
    }

    /** Effective width per column: declared width, else measured content. */
    private fun columnWidths(sheet: Sheet, paint: TextPaint): FloatArray {
        val widths = FloatArray(sheet.maxCol + 1)
        for (c in 0..sheet.maxCol) {
            val declared = sheet.colWidths[c]
            if (declared != null) {
                widths[c] = declared
            } else {
                var maxW = 0f
                var probed = 0
                for ((_, cells) in sheet.rows) {
                    val t = cells[c]?.text ?: continue
                    maxW = max(maxW, paint.measureText(t))
                    if (++probed >= 200) break // sampling is enough
                }
                widths[c] = (maxW + 2 * CELL_PAD + 2f).coerceIn(28f, 170f)
            }
        }
        return widths
    }

    /** Only render gaps of up to 2 empty rows; collapse longer runs. */
    private fun rowGapSkippable(sheet: Sheet, row: Int): Boolean {
        var emptyBefore = 0
        var r = row - 1
        while (r >= 0 && sheet.rows[r] == null) { emptyBefore++; r-- }
        return emptyBefore >= 2
    }

    private fun columnName(index: Int): String {
        var i = index + 1
        val sb = StringBuilder()
        while (i > 0) {
            val rem = (i - 1) % 26
            sb.insert(0, ('A' + rem))
            i = (i - 1) / 26
        }
        return sb.toString()
    }

    companion object {
        private const val MARGIN = 36
        private const val ROW_H = 15f
        private const val CELL_PAD = 3f
        private const val MAX_ROWS = 5000
    }
}
