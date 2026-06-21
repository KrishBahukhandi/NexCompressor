package com.nexcompress.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nexcompress.app.data.processor.FileStorageManager
import com.nexcompress.app.data.processor.PdfAnnotator
import com.nexcompress.app.data.processor.PdfFiles
import com.nexcompress.app.data.processor.PdfPageRenderer
import com.nexcompress.app.domain.model.FileType
import com.nexcompress.app.domain.model.ImageAnnotation
import com.nexcompress.app.domain.model.InkAnnotation
import com.nexcompress.app.domain.model.NormPoint
import com.nexcompress.app.domain.model.OutputItem
import com.nexcompress.app.domain.model.PickedFile
import com.nexcompress.app.domain.model.TextAnnotation
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device checks for the markup editor engine: annotations land where placed
 * on the chosen page, and pages without annotations keep their selectable text.
 */
@RunWith(AndroidJUnit4::class)
class PdfAnnotatorTest {

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

    @Test
    fun annotate_stampsInkAtPlacement_andKeepsOtherPagesText() = runBlocking<Unit> {
        val pdf = makeTextPdf(listOf("alpha page", "beta page"))
        try {
            val input = PickedFile(Uri.fromFile(pdf).toString(), pdf.name, pdf.length(), FileType.PDF)
            val annotations = listOf(
                InkAnnotation(
                    pageIndex = 0,
                    points = listOf(NormPoint(0.2f, 0.5f), NormPoint(0.8f, 0.5f)),
                    widthFrac = 0.02f,
                    colorArgb = Color.rgb(220, 0, 0),
                    highlighter = false
                )
            )
            val item = PdfAnnotator(context, storage)
                .annotate(input, annotations, "test_annotate")
                .items.single().also { outputs.add(it) }

            val staged = stage(item.uri)
            try {
                PDDocument.load(staged).use { doc ->
                    assertEquals(2, doc.numberOfPages)
                    // Page 2 had no annotations → must stay real, selectable text.
                    val stripper = PDFTextStripper().apply { startPage = 2; endPage = 2 }
                    assertTrue(
                        "un-annotated page lost its text",
                        stripper.getText(doc).contains("beta page")
                    )
                    // Page 1 WAS annotated, but markup is laid down as a vector
                    // overlay now — its underlying text must STILL be selectable
                    // (the old pipeline rasterized the whole page and lost it).
                    val page1 = PDFTextStripper().apply { startPage = 1; endPage = 1 }
                    assertTrue(
                        "annotated page was rasterized — its text is no longer selectable",
                        page1.getText(doc).contains("alpha page")
                    )
                }
            } finally {
                staged.delete()
            }

            val rendered = renderPage(item.uri, 0)
            try {
                assertTrue(
                    "ink stroke missing where it was drawn",
                    redRatio(rendered, 0.30f, 0.46f, 0.40f, 0.08f) > 0.1f
                )
                assertEquals(
                    "ink bled into an untouched corner",
                    0f, redRatio(rendered, 0.0f, 0.0f, 0.18f, 0.18f)
                )
            } finally {
                rendered.recycle()
            }
        } finally {
            pdf.delete()
        }
    }

    @Test
    fun annotate_flattensTextNote() = runBlocking<Unit> {
        val pdf = makeTextPdf(listOf("page one"))
        try {
            val input = PickedFile(Uri.fromFile(pdf).toString(), pdf.name, pdf.length(), FileType.PDF)
            val annotations = listOf(
                TextAnnotation(
                    pageIndex = 0,
                    text = "REVIEWED",
                    left = 0.15f,
                    top = 0.80f, // lower area, away from the page's own top text
                    fontFrac = 0.05f,
                    colorArgb = Color.rgb(220, 0, 0),
                    font = com.nexcompress.app.domain.model.AnnotationFont.SERIF,
                    bold = true
                )
            )
            val item = PdfAnnotator(context, storage)
                .annotate(input, annotations, "test_annotate_text")
                .items.single().also { outputs.add(it) }

            val rendered = renderPage(item.uri, 0)
            try {
                assertTrue(
                    "text note not rendered where placed",
                    redRatio(rendered, 0.12f, 0.76f, 0.5f, 0.12f) > 0.01f
                )
            } finally {
                rendered.recycle()
            }
        } finally {
            pdf.delete()
        }
    }

    @Test
    fun annotate_placesSignatureImageAtPlacement() = runBlocking<Unit> {
        val pdf = makeTextPdf(listOf("page one"))
        try {
            val input = PickedFile(Uri.fromFile(pdf).toString(), pdf.name, pdf.length(), FileType.PDF)
            // A small red square PNG standing in for a drawn signature.
            val png = ByteArrayOutputStream().use { baos ->
                val b = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
                b.eraseColor(Color.rgb(220, 0, 0))
                b.compress(Bitmap.CompressFormat.PNG, 100, baos)
                b.recycle()
                baos.toByteArray()
            }
            val item = PdfAnnotator(context, storage)
                .annotate(
                    input,
                    listOf(ImageAnnotation(0, png, left = 0.25f, top = 0.55f, widthFrac = 0.4f)),
                    "test_sig"
                )
                .items.single().also { outputs.add(it) }

            val rendered = renderPage(item.uri, 0)
            try {
                assertTrue(
                    "signature image missing where it was placed",
                    redRatio(rendered, 0.27f, 0.57f, 0.34f, 0.10f) > 0.4f
                )
                assertEquals(
                    "signature bled into an untouched corner",
                    0f, redRatio(rendered, 0.0f, 0.0f, 0.15f, 0.15f)
                )
            } finally {
                rendered.recycle()
            }
        } finally {
            pdf.delete()
        }
    }

    @Test
    fun annotate_noAnnotations_isRejected() = runBlocking<Unit> {
        val pdf = makeTextPdf(listOf("only page"))
        try {
            val input = PickedFile(Uri.fromFile(pdf).toString(), pdf.name, pdf.length(), FileType.PDF)
            var threw = false
            try {
                PdfAnnotator(context, storage).annotate(input, emptyList(), "test_empty")
            } catch (e: com.nexcompress.app.domain.model.CompressionException) {
                threw = true
            }
            assertTrue("empty annotation set should be rejected", threw)
        } finally {
            pdf.delete()
        }
    }

    // ------------------------------------------------------------------ //

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

    /** Fraction of sampled pixels in the normalized region that are annotation-red. */
    private fun redRatio(bmp: Bitmap, l: Float, t: Float, w: Float, h: Float): Float {
        val x0 = (bmp.width * l).toInt().coerceIn(0, bmp.width - 1)
        val y0 = (bmp.height * t).toInt().coerceIn(0, bmp.height - 1)
        val x1 = (bmp.width * (l + w)).toInt().coerceIn(x0 + 1, bmp.width)
        val y1 = (bmp.height * (t + h)).toInt().coerceIn(y0 + 1, bmp.height)
        var red = 0
        var total = 0
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                val c = bmp.getPixel(x, y)
                if (Color.red(c) > 150 && Color.green(c) < 110 && Color.blue(c) < 110) red++
                total++
                x += 2
            }
            y += 2
        }
        return if (total == 0) 0f else red.toFloat() / total
    }

    private fun makeTextPdf(pageTexts: List<String>): File {
        val doc = PDDocument()
        try {
            for (text in pageTexts) {
                val page = PDPage(PDRectangle.LETTER)
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText()
                    cs.setFont(PDType1Font.HELVETICA, 14f)
                    cs.newLineAtOffset(72f, 720f)
                    cs.showText(text)
                    cs.endText()
                }
            }
            val f = File(context.cacheDir, "annot_fixture_${System.nanoTime()}.pdf")
            doc.save(f)
            return f
        } finally {
            doc.close()
        }
    }
}
