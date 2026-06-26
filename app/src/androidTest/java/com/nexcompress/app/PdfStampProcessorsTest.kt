package com.nexcompress.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nexcompress.app.data.processor.FileStorageManager
import com.nexcompress.app.data.processor.OcrPdfConverter
import com.nexcompress.app.data.processor.PdfFiles
import com.nexcompress.app.data.processor.PdfPageNumberer
import com.nexcompress.app.data.processor.PdfPageRenderer
import com.nexcompress.app.data.processor.PdfWatermarker
import com.nexcompress.app.data.processor.ScannedDocumentSaver
import com.nexcompress.app.domain.model.FileType
import com.nexcompress.app.domain.model.OutputItem
import com.nexcompress.app.domain.model.PageNumberFormat
import com.nexcompress.app.domain.model.PageNumberSpec
import com.nexcompress.app.domain.model.PickedFile
import com.nexcompress.app.domain.model.StampPosition
import com.nexcompress.app.domain.model.WatermarkSpec
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device checks for the "stamp" processors: watermark, page numbers, and the
 * searchable-OCR layer. Each must produce its visible result WITHOUT destroying
 * the page's existing selectable text.
 */
@RunWith(AndroidJUnit4::class)
class PdfStampProcessorsTest {

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
    // Watermark                                                          //
    // ------------------------------------------------------------------ //

    @Test
    fun watermark_stampsMark_andKeepsPageTextSelectable() = runBlocking<Unit> {
        val pdf = makeTextPdf(listOf("alpha page", "beta page"))
        try {
            val input = PickedFile(Uri.fromFile(pdf).toString(), pdf.name, pdf.length(), FileType.PDF)
            // Opaque black for a robust pixel check; the in-app default is translucent.
            val spec = WatermarkSpec("CONFIDENTIAL", opacity = 1f, diagonal = true, colorArgb = Color.BLACK)
            val item = PdfWatermarker(context, storage)
                .apply(input, spec, "test_watermark")
                .items.single().also { outputs.add(it) }

            val staged = stage(item.uri)
            try {
                PDDocument.load(staged).use { doc ->
                    assertEquals(2, doc.numberOfPages)
                    // The mark is an overlay — the page's own text must survive.
                    assertTrue(
                        "watermarking destroyed the page text",
                        PDFTextStripper().getText(doc).contains("alpha page")
                    )
                }
            } finally {
                staged.delete()
            }

            // The diagonal mark must actually land across the middle of page 1.
            val rendered = renderPage(item.uri, 0)
            try {
                assertTrue(
                    "watermark not drawn across the page",
                    darkRatio(rendered, 0.20f, 0.40f, 0.60f, 0.20f) > 0.01f
                )
            } finally {
                rendered.recycle()
            }
        } finally {
            pdf.delete()
        }
    }

    /**
     * Tiled (mosaic) mode must repeat the mark across the WHOLE page — including
     * the off-diagonal corners a single centered mark never reaches.
     */
    @Test
    fun watermark_tiled_coversOffDiagonalCorners() = runBlocking<Unit> {
        val pdf = makeTextPdf(listOf("page"))
        try {
            val input = PickedFile(Uri.fromFile(pdf).toString(), pdf.name, pdf.length(), FileType.PDF)
            val spec = WatermarkSpec("DRAFT", opacity = 1f, diagonal = true, colorArgb = Color.BLACK, tiled = true)
            val item = PdfWatermarker(context, storage)
                .apply(input, spec, "test_wm_tiled")
                .items.single().also { outputs.add(it) }

            val rendered = renderPage(item.uri, 0)
            try {
                // The main diagonal runs top-left→bottom-right; a single mark would
                // leave the top-RIGHT and bottom-LEFT corners blank. Tiled fills them.
                assertTrue(
                    "tiled watermark missing from the top-right corner",
                    darkRatio(rendered, 0.74f, 0.04f, 0.22f, 0.20f) > 0f
                )
                assertTrue(
                    "tiled watermark missing from the bottom-left corner",
                    darkRatio(rendered, 0.04f, 0.76f, 0.22f, 0.20f) > 0f
                )
            } finally {
                rendered.recycle()
            }
        } finally {
            pdf.delete()
        }
    }

    // ------------------------------------------------------------------ //
    // Page numbers                                                       //
    // ------------------------------------------------------------------ //

    @Test
    fun pageNumbers_stampXofN_onEveryPage() = runBlocking<Unit> {
        val pdf = makeTextPdf(listOf("one", "two", "three"))
        try {
            val input = PickedFile(Uri.fromFile(pdf).toString(), pdf.name, pdf.length(), FileType.PDF)
            val spec = PageNumberSpec(
                format = PageNumberFormat.PAGE_OF_TOTAL,
                position = StampPosition.BOTTOM_CENTER,
                startNumber = 1
            )
            val item = PdfPageNumberer(context, storage)
                .apply(input, spec, "test_pagenum")
                .items.single().also { outputs.add(it) }

            val staged = stage(item.uri)
            try {
                PDDocument.load(staged).use { doc ->
                    assertEquals(3, doc.numberOfPages)
                    for (p in 1..3) {
                        val pageText = PDFTextStripper().apply { startPage = p; endPage = p }.getText(doc)
                        assertTrue(
                            "page $p missing its '$p of 3' stamp",
                            pageText.contains("$p of 3")
                        )
                    }
                }
            } finally {
                staged.delete()
            }
        } finally {
            pdf.delete()
        }
    }

    /** Skip-first leaves the cover unnumbered; the start number offsets the rest. */
    @Test
    fun pageNumbers_skipFirstPage_andCustomStart() = runBlocking<Unit> {
        val pdf = makeTextPdf(listOf("cover", "body one", "body two"))
        try {
            val input = PickedFile(Uri.fromFile(pdf).toString(), pdf.name, pdf.length(), FileType.PDF)
            val spec = PageNumberSpec(
                format = PageNumberFormat.PAGE_OF_TOTAL,
                position = StampPosition.BOTTOM_CENTER,
                startNumber = 5,
                skipFirstPage = true
            )
            val item = PdfPageNumberer(context, storage)
                .apply(input, spec, "test_pagenum_skip")
                .items.single().also { outputs.add(it) }

            val staged = stage(item.uri)
            try {
                PDDocument.load(staged).use { doc ->
                    fun pageText(p: Int) = PDFTextStripper().apply { startPage = p; endPage = p }.getText(doc)
                    // Cover (page 1) must carry NO number; "of 6" counts the 2 numbered pages.
                    assertTrue("cover should not be numbered", !pageText(1).contains("of 6"))
                    assertTrue("page 2 should be '5 of 6'", pageText(2).contains("5 of 6"))
                    assertTrue("page 3 should be '6 of 6'", pageText(3).contains("6 of 6"))
                }
            } finally {
                staged.delete()
            }
        } finally {
            pdf.delete()
        }
    }

    // ------------------------------------------------------------------ //
    // Searchable OCR layer                                               //
    // ------------------------------------------------------------------ //

    @Test
    fun ocrPdf_addsSelectableTextLayerToScan() = runBlocking<Unit> {
        val pdf = buildScannedTextPdf(listOf("Invoice Summary", "Total Amount Due"))
        try {
            val input = PickedFile(Uri.fromFile(pdf).toString(), pdf.name, pdf.length(), FileType.PDF)
            val item = OcrPdfConverter(context, storage)
                .convert(input, "test_ocr_pdf")
                .items.single().also { outputs.add(it) }

            val staged = stage(item.uri)
            try {
                PDDocument.load(staged).use { doc ->
                    assertEquals(1, doc.numberOfPages)
                    val text = PDFTextStripper().getText(doc).lowercase()
                    // The scan had no text layer before; OCR should now make most
                    // of the rendered words selectable (allow a miss or two).
                    val recovered = listOf("invoice", "summary", "total", "amount", "due")
                        .count { text.contains(it) }
                    assertTrue("OCR layer recovered too few words ($recovered/5)", recovered >= 3)
                }
            } finally {
                staged.delete()
            }
        } finally {
            pdf.delete()
        }
    }

    /**
     * The original scan image must be PRESERVED at full resolution — only an
     * invisible text layer is added. (The old pipeline re-rastered every page to
     * ~2200 px, softening a 300-dpi scan; that would be "average".)
     */
    @Test
    fun ocrPdf_preservesOriginalScanResolution() = runBlocking<Unit> {
        // A 300-dpi-class scan: a 2550×3300 page image.
        val pdf = buildScannedTextPdf(
            listOf("Invoice Summary", "Total Amount Due"),
            width = 2550, height = 3300, textSize = 150f
        )
        try {
            val input = PickedFile(Uri.fromFile(pdf).toString(), pdf.name, pdf.length(), FileType.PDF)
            val item = OcrPdfConverter(context, storage)
                .convert(input, "test_ocr_res")
                .items.single().also { outputs.add(it) }

            val staged = stage(item.uri)
            try {
                PDDocument.load(staged).use { doc ->
                    // The page's embedded image must still be the full-res original,
                    // not a down-sampled re-render.
                    var maxImageEdge = 0
                    val res = doc.getPage(0).resources
                    for (name in res.xObjectNames) {
                        val xobj = runCatching { res.getXObject(name) }.getOrNull()
                        if (xobj is PDImageXObject) {
                            maxImageEdge = maxOf(maxImageEdge, xobj.width, xobj.height)
                        }
                    }
                    assertTrue(
                        "scan was down-sampled (max image edge = $maxImageEdge px)",
                        maxImageEdge >= 3000
                    )
                    assertTrue(
                        "no searchable text layer was added",
                        PDFTextStripper().getText(doc).lowercase().contains("invoice")
                    )
                }
            } finally {
                staged.delete()
            }
        } finally {
            pdf.delete()
        }
    }

    /**
     * The hidden text layer must use an embedded Unicode font so accented Latin
     * stays searchable (the ASCII-only fallback would strip é/ñ/ü). Deterministic:
     * assert a Type0 (Unicode, embedded) font is present on the OCR'd page.
     */
    @Test
    fun ocrPdf_embedsUnicodeFontForAccents() = runBlocking<Unit> {
        val pdf = buildScannedTextPdf(listOf("Cafe Resume Metro", "Naive Zurich"))
        try {
            val input = PickedFile(Uri.fromFile(pdf).toString(), pdf.name, pdf.length(), FileType.PDF)
            val item = OcrPdfConverter(context, storage)
                .convert(input, "test_ocr_unicode")
                .items.single().also { outputs.add(it) }

            val staged = stage(item.uri)
            try {
                PDDocument.load(staged).use { doc ->
                    val res = doc.getPage(0).resources
                    var hasUnicodeFont = false
                    for (name in res.fontNames) {
                        if (runCatching { res.getFont(name) }.getOrNull() is PDType0Font) {
                            hasUnicodeFont = true
                        }
                    }
                    assertTrue("OCR layer did not embed a Unicode font", hasUnicodeFont)
                    assertTrue(
                        "OCR recovered no text",
                        PDFTextStripper().getText(doc).lowercase().contains("caf")
                    )
                }
            } finally {
                staged.delete()
            }
        } finally {
            pdf.delete()
        }
    }

    /** A /Rotate'd scan must still convert (via the upright raster fallback). */
    @Test
    fun ocrPdf_rotatedScan_producesUprightPage() = runBlocking<Unit> {
        val pdf = buildScannedTextPdf(listOf("Rotated Scan Page"), rotation = 90)
        try {
            val input = PickedFile(Uri.fromFile(pdf).toString(), pdf.name, pdf.length(), FileType.PDF)
            val item = OcrPdfConverter(context, storage)
                .convert(input, "test_ocr_rot")
                .items.single().also { outputs.add(it) }

            val staged = stage(item.uri)
            try {
                PDDocument.load(staged).use { doc ->
                    assertEquals(1, doc.numberOfPages)
                    val page = doc.getPage(0)
                    // The fallback bakes the rotation into an upright page: a LETTER
                    // page rotated 90° becomes a landscape page with /Rotate 0.
                    assertEquals("fallback page should be upright", 0, page.rotation)
                    assertTrue(
                        "rotated page was not laid out landscape (upright)",
                        page.cropBox.width > page.cropBox.height
                    )
                }
            } finally {
                staged.delete()
            }
        } finally {
            pdf.delete()
        }
    }

    // ------------------------------------------------------------------ //
    // Rotated-page handling (the "average" failure mode)                 //
    // ------------------------------------------------------------------ //

    /**
     * On a /Rotate 90 page the number must land at the *visual* bottom (where the
     * reader sees the footer) — not wherever the un-rotated user space puts it.
     */
    @Test
    fun pageNumbers_onRotatedPage_landAtVisualBottom() = runBlocking<Unit> {
        val pdf = makeRotatedBlankPdf(rotation = 90)
        try {
            val input = PickedFile(Uri.fromFile(pdf).toString(), pdf.name, pdf.length(), FileType.PDF)
            val spec = PageNumberSpec(
                format = PageNumberFormat.PAGE_OF_TOTAL,
                position = StampPosition.BOTTOM_CENTER,
                startNumber = 1
            )
            val item = PdfPageNumberer(context, storage)
                .apply(input, spec, "test_pagenum_rot")
                .items.single().also { outputs.add(it) }

            // Rendered upright, the number must be in the bottom strip, not the top.
            val rendered = renderPage(item.uri, 0)
            try {
                assertTrue(
                    "rendered page should be landscape (rotation applied)",
                    rendered.width > rendered.height
                )
                assertTrue(
                    "page number missing from the visual bottom",
                    darkRatio(rendered, 0.0f, 0.86f, 1.0f, 0.14f) > 0f
                )
                assertEquals(
                    "page number wrongly placed at the visual top",
                    0f, darkRatio(rendered, 0.0f, 0.0f, 1.0f, 0.14f)
                )
            } finally {
                rendered.recycle()
            }
        } finally {
            pdf.delete()
        }
    }

    /** A diagonal watermark must still stamp cleanly on a /Rotate'd page. */
    @Test
    fun watermark_onRotatedPage_stillStamps() = runBlocking<Unit> {
        val pdf = makeRotatedBlankPdf(rotation = 90)
        try {
            val input = PickedFile(Uri.fromFile(pdf).toString(), pdf.name, pdf.length(), FileType.PDF)
            val spec = WatermarkSpec("CONFIDENTIAL", opacity = 1f, diagonal = true, colorArgb = Color.BLACK)
            val item = PdfWatermarker(context, storage)
                .apply(input, spec, "test_wm_rot")
                .items.single().also { outputs.add(it) }

            val rendered = renderPage(item.uri, 0)
            try {
                assertTrue(
                    "watermark not drawn on the rotated page",
                    darkRatio(rendered, 0.20f, 0.40f, 0.60f, 0.20f) > 0.01f
                )
            } finally {
                rendered.recycle()
            }
        } finally {
            pdf.delete()
        }
    }

    // ------------------------------------------------------------------ //
    // Document scanner — saver step                                      //
    // ------------------------------------------------------------------ //

    @Test
    fun scannedDocumentSaver_persistsScanIntoOutput() = runBlocking<Unit> {
        // Stands in for the PDF the ML Kit scanner hands back via a content URI.
        val pdf = makeTextPdf(listOf("scanned page one", "scanned page two"))
        try {
            val item = ScannedDocumentSaver(context, storage)
                .save(Uri.fromFile(pdf), "test_scan_save")
                .items.single().also { outputs.add(it) }
            assertTrue("scan must be saved as a .pdf", item.displayName.endsWith(".pdf"))

            val staged = stage(item.uri)
            try {
                PDDocument.load(staged).use { doc ->
                    assertEquals("the scanned PDF was not copied intact", 2, doc.numberOfPages)
                    assertTrue(PDFTextStripper().getText(doc).contains("scanned page one"))
                }
            } finally {
                staged.delete()
            }
        } finally {
            pdf.delete()
        }
    }

    @Test
    fun scannedDocumentSaver_savesPagesAsImages() = runBlocking<Unit> {
        // Stand-ins for the JPEG pages the ML Kit scanner returns.
        val p1 = makeJpegFile(Color.RED)
        val p2 = makeJpegFile(Color.BLUE)
        try {
            val result = ScannedDocumentSaver(context, storage)
                .saveImages(listOf(Uri.fromFile(p1), Uri.fromFile(p2)), "test_scan_imgs")
            result.items.forEach { outputs.add(it) }
            assertEquals("one image per scanned page", 2, result.items.size)
            result.items.forEach {
                assertTrue("scan page should be a .jpg", it.displayName.endsWith(".jpg"))
                assertTrue("scan page should be non-empty", it.outputSize > 0)
            }
        } finally {
            p1.delete()
            p2.delete()
        }
    }

    // ------------------------------------------------------------------ //
    // Fixtures & helpers                                                 //
    // ------------------------------------------------------------------ //

    private fun makeJpegFile(color: Int): File {
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(color)
        val f = File(context.cacheDir, "page_${System.nanoTime()}.jpg")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bmp.recycle()
        return f
    }

    private fun stage(uriString: String): File =
        PdfFiles.copyToCache(context, Uri.parse(uriString), "verify_")

    private fun renderPage(uriString: String, index: Int): Bitmap {
        val renderer = PdfPageRenderer(context, Uri.parse(uriString))
        try {
            return renderer.renderPage(index, 1200) ?: error("could not render page")
        } finally {
            renderer.close()
        }
    }

    /** Fraction of sampled pixels in the region that are dark (drawn-on). */
    private fun darkRatio(bmp: Bitmap, l: Float, t: Float, w: Float, h: Float): Float {
        val x0 = (bmp.width * l).toInt().coerceIn(0, bmp.width - 1)
        val y0 = (bmp.height * t).toInt().coerceIn(0, bmp.height - 1)
        val x1 = (bmp.width * (l + w)).toInt().coerceIn(x0 + 1, bmp.width)
        val y1 = (bmp.height * (t + h)).toInt().coerceIn(y0 + 1, bmp.height)
        var dark = 0
        var total = 0
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                val c = bmp.getPixel(x, y)
                if ((Color.red(c) + Color.green(c) + Color.blue(c)) / 3 < 110) dark++
                total++
                x += 2
            }
            y += 2
        }
        return if (total == 0) 0f else dark.toFloat() / total
    }

    private fun makeTextPdf(pages: List<String>): File {
        val doc = PDDocument()
        try {
            for (t in pages) {
                val page = PDPage(PDRectangle.LETTER)
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText()
                    cs.setFont(PDType1Font.HELVETICA, 14f)
                    cs.newLineAtOffset(72f, 720f)
                    cs.showText(t)
                    cs.endText()
                }
            }
            val f = File(context.cacheDir, "stamp_fixture_${System.nanoTime()}.pdf")
            doc.save(f)
            return f
        } finally {
            doc.close()
        }
    }

    /** A scanned-style page: text drawn into a bitmap, embedded as a full-page image. */
    private fun buildScannedTextPdf(
        lines: List<String>,
        width: Int = 1000,
        height: Int = 1294,
        textSize: Float = 70f,
        rotation: Int = 0
    ): File {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            this.textSize = textSize
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
        var y = height * 0.17f
        for (line in lines) {
            canvas.drawText(line, width * 0.09f, y, paint)
            y += textSize * 2.1f
        }
        val doc = PDDocument()
        try {
            val page = PDPage(PDRectangle.LETTER)
            if (rotation != 0) page.rotation = rotation
            doc.addPage(page)
            val img = JPEGFactory.createFromImage(doc, bmp, 0.9f)
            PDPageContentStream(doc, page).use { cs ->
                cs.drawImage(img, 0f, 0f, PDRectangle.LETTER.width, PDRectangle.LETTER.height)
            }
            val f = File(context.cacheDir, "scan_fixture_${System.nanoTime()}.pdf")
            doc.save(f)
            return f
        } finally {
            doc.close()
            bmp.recycle()
        }
    }

    /** A blank white LETTER page carrying the given /Rotate, for corner-placement checks. */
    private fun makeRotatedBlankPdf(rotation: Int): File {
        val doc = PDDocument()
        try {
            val page = PDPage(PDRectangle.LETTER)
            page.rotation = rotation
            doc.addPage(page)
            val f = File(context.cacheDir, "rot_fixture_${System.nanoTime()}.pdf")
            doc.save(f)
            return f
        } finally {
            doc.close()
        }
    }
}
