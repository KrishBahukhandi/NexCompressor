package com.nexcompress.app

import android.content.Context
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nexcompress.app.data.processor.DocxToPdfConverter
import com.nexcompress.app.data.processor.FileStorageManager
import com.nexcompress.app.data.processor.PdfFiles
import com.nexcompress.app.data.processor.PdfToDocxConverter
import com.nexcompress.app.data.processor.PdfToPptxConverter
import com.nexcompress.app.data.processor.XlsxToPdfConverter
import com.nexcompress.app.domain.model.FileType
import com.nexcompress.app.domain.model.OutputItem
import com.nexcompress.app.domain.model.PickedFile
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device behavior checks for the offline Office converters: real content
 * must survive each conversion, and generated OOXML packages must be
 * structurally valid.
 */
@RunWith(AndroidJUnit4::class)
class OfficeConvertersTest {

    private lateinit var context: Context
    private lateinit var storage: FileStorageManager
    private val outputs = mutableListOf<OutputItem>()

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        PDFBoxResourceLoader.init(context)
        storage = FileStorageManager(context)
    }

    @After
    fun tearDown() {
        outputs.forEach { item ->
            runCatching { context.contentResolver.delete(Uri.parse(item.uri), null, null) }
        }
        outputs.clear()
    }

    // ------------------------------------------------------------------ //
    // Word → PDF                                                         //
    // ------------------------------------------------------------------ //

    @Test
    fun docxToPdf_keepsHeadingBodyAndTableText() = runBlocking<Unit> {
        val docx = buildDocxFixture()
        try {
            val item = DocxToPdfConverter(context, storage)
                .convert(pickedFile(docx), "test_docx2pdf")
                .items.single().also { outputs.add(it) }
            assertTrue(item.displayName.endsWith(".pdf"))

            val staged = stage(item.uri)
            try {
                PDDocument.load(staged).use { doc ->
                    assertTrue(doc.numberOfPages >= 1)
                    val text = PDFTextStripper().getText(doc)
                    assertTrue("heading lost", text.contains("Quarterly Report"))
                    assertTrue("bold run lost", text.contains("fourteen percent"))
                    assertTrue("table cell lost", text.contains("CellAlpha"))
                    assertTrue("table cell lost", text.contains("CellBeta"))
                }
            } finally {
                staged.delete()
            }
        } finally {
            docx.delete()
        }
    }

    // ------------------------------------------------------------------ //
    // Excel → PDF                                                        //
    // ------------------------------------------------------------------ //

    @Test
    fun xlsxToPdf_rendersSharedStringsNumbersAndDates() = runBlocking<Unit> {
        val xlsx = buildXlsxFixture()
        try {
            val item = XlsxToPdfConverter(context, storage)
                .convert(pickedFile(xlsx), "test_xlsx2pdf")
                .items.single().also { outputs.add(it) }

            val staged = stage(item.uri)
            try {
                PDDocument.load(staged).use { doc ->
                    assertTrue(doc.numberOfPages >= 1)
                    val text = PDFTextStripper().getText(doc)
                    assertTrue("sheet name lost", text.contains("Data"))
                    assertTrue("shared string lost", text.contains("Widget"))
                    assertTrue("number lost", text.contains("42.5"))
                    assertTrue("date style not applied", text.contains("2023-03-15"))
                }
            } finally {
                staged.delete()
            }
        } finally {
            xlsx.delete()
        }
    }

    // ------------------------------------------------------------------ //
    // PDF → Word                                                         //
    // ------------------------------------------------------------------ //

    @Test
    fun pdfToDocx_extractsTextWithHeadingsAndPageBreaks() = runBlocking<Unit> {
        val pdf = buildPdfFixture(
            pages = listOf(
                listOf(24f to "Annual Summary", 12f to "Profits remained stable throughout."),
                listOf(12f to "Second page content marker.")
            )
        )
        try {
            val item = PdfToDocxConverter(context, storage)
                .convert(pickedFile(pdf), "test_pdf2docx")
                .items.single().also { outputs.add(it) }
            assertTrue(item.displayName.endsWith(".docx"))

            val staged = stage(item.uri)
            try {
                ZipFile(staged).use { zip ->
                    assertNotNull("missing styles part", zip.getEntry("word/styles.xml"))
                    val docXml = zip.getInputStream(zip.getEntry("word/document.xml"))
                        .use { it.readBytes().toString(Charsets.UTF_8) }
                    assertTrue("title text lost", docXml.contains("Annual Summary"))
                    assertTrue("body text lost", docXml.contains("Profits remained stable"))
                    assertTrue("page 2 text lost", docXml.contains("Second page content marker."))
                    assertTrue("title not styled as heading", docXml.contains("Heading1"))
                    assertTrue("missing page break", docXml.contains("""w:type="page""""))
                }
            } finally {
                staged.delete()
            }
        } finally {
            pdf.delete()
        }
    }

    // ------------------------------------------------------------------ //
    // PDF → PowerPoint                                                   //
    // ------------------------------------------------------------------ //

    @Test
    fun pdfToPptx_oneSlideImagePerPage() = runBlocking<Unit> {
        val pdf = buildPdfFixture(
            pages = listOf(
                listOf(20f to "Slide one source"),
                listOf(20f to "Slide two source")
            )
        )
        try {
            val item = PdfToPptxConverter(context, storage)
                .convert(pickedFile(pdf), "test_pdf2pptx")
                .items.single().also { outputs.add(it) }
            assertTrue(item.displayName.endsWith(".pptx"))

            val staged = stage(item.uri)
            try {
                ZipFile(staged).use { zip ->
                    for (part in listOf(
                        "ppt/presentation.xml",
                        "ppt/slideMasters/slideMaster1.xml",
                        "ppt/slideLayouts/slideLayout1.xml",
                        "ppt/theme/theme1.xml",
                        "ppt/slides/slide1.xml",
                        "ppt/slides/slide2.xml",
                        "ppt/media/image1.jpg",
                        "ppt/media/image2.jpg"
                    )) {
                        assertNotNull("missing $part", zip.getEntry(part))
                    }
                    val pres = zip.getInputStream(zip.getEntry("ppt/presentation.xml"))
                        .use { it.readBytes().toString(Charsets.UTF_8) }
                    assertEquals("expected exactly 2 slides", 2, "<p:sldId ".toRegex().findAll(pres).count())
                    val slide1 = zip.getInputStream(zip.getEntry("ppt/slides/slide1.xml"))
                        .use { it.readBytes().toString(Charsets.UTF_8) }
                    assertTrue("slide must embed its image", slide1.contains("r:embed"))
                    // The slide image must hold real rendered content.
                    val img = zip.getInputStream(zip.getEntry("ppt/media/image1.jpg"))
                        .use { it.readBytes() }
                    assertTrue("slide image suspiciously small", img.size > 5_000)
                }
            } finally {
                staged.delete()
            }
        } finally {
            pdf.delete()
        }
    }

    // ------------------------------------------------------------------ //
    // Fixtures                                                           //
    // ------------------------------------------------------------------ //

    private fun pickedFile(f: File) =
        PickedFile(Uri.fromFile(f).toString(), f.name, f.length(), FileType.DOCUMENT)

    private fun stage(uriString: String): File =
        PdfFiles.copyToCache(context, Uri.parse(uriString), "verify_")

    private fun zipFixture(name: String, parts: Map<String, String>): File {
        val f = File(context.cacheDir, "${name}_${System.nanoTime()}.$name")
        ZipOutputStream(f.outputStream()).use { zip ->
            parts.forEach { (entry, content) ->
                zip.putNextEntry(ZipEntry(entry))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return f
    }

    private fun buildDocxFixture(): File = zipFixture(
        "docx",
        mapOf(
            "[Content_Types].xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
</Types>""",
            "_rels/.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>""",
            "word/document.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
<w:body>
<w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>Quarterly Report</w:t></w:r></w:p>
<w:p><w:r><w:t xml:space="preserve">Revenue grew by </w:t></w:r><w:r><w:rPr><w:b/></w:rPr><w:t>fourteen percent</w:t></w:r></w:p>
<w:tbl>
<w:tblGrid><w:gridCol w:w="4000"/><w:gridCol w:w="4000"/></w:tblGrid>
<w:tr><w:tc><w:p><w:r><w:t>CellAlpha</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>CellBeta</w:t></w:r></w:p></w:tc></w:tr>
</w:tbl>
<w:sectPr><w:pgSz w:w="12240" w:h="15840"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/></w:sectPr>
</w:body>
</w:document>"""
        )
    )

    private fun buildXlsxFixture(): File = zipFixture(
        "xlsx",
        mapOf(
            "[Content_Types].xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
</Types>""",
            "_rels/.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>""",
            "xl/workbook.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
<sheets><sheet name="Data" sheetId="1" r:id="rId1"/></sheets>
</workbook>""",
            "xl/_rels/workbook.xml.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
</Relationships>""",
            "xl/sharedStrings.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="3" uniqueCount="3">
<si><t>Item</t></si><si><t>Qty</t></si><si><t>Widget</t></si>
</sst>""",
            "xl/styles.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<cellXfs count="2"><xf numFmtId="0"/><xf numFmtId="14"/></cellXfs>
</styleSheet>""",
            "xl/worksheets/sheet1.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<sheetData>
<row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c></row>
<row r="2"><c r="A2" t="s"><v>2</v></c><c r="B2"><v>42.5</v></c><c r="C2" s="1"><v>45000</v></c></row>
</sheetData>
</worksheet>"""
        )
    )

    /** Builds a PDF where each page holds (fontSize -> line) entries. */
    private fun buildPdfFixture(pages: List<List<Pair<Float, String>>>): File {
        val doc = PDDocument()
        try {
            for (lines in pages) {
                val page = PDPage(PDRectangle.LETTER)
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    var y = 720f
                    for ((size, text) in lines) {
                        cs.beginText()
                        cs.setFont(PDType1Font.HELVETICA, size)
                        cs.newLineAtOffset(72f, y)
                        cs.showText(text)
                        cs.endText()
                        y -= size * 2
                    }
                }
            }
            val f = File(context.cacheDir, "fixture_${System.nanoTime()}.pdf")
            doc.save(f)
            return f
        } finally {
            doc.close()
        }
    }
}
