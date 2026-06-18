package com.nexcompress.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nexcompress.app.data.processor.FileStorageManager
import com.nexcompress.app.data.processor.PdfCompressor
import com.nexcompress.app.data.processor.PdfFiles
import com.nexcompress.app.data.processor.PdfPageRenderer
import com.nexcompress.app.domain.model.CompressionProfile
import com.nexcompress.app.domain.model.FileType
import com.nexcompress.app.domain.model.OutputItem
import com.nexcompress.app.domain.model.PickedFile
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Random
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device behavior checks for the PDF processors:
 *  - compression must shrink image-heavy documents WITHOUT rasterizing text
 *  - signing must keep every page lossless and put the ink exactly where the
 *    normalized placement says, including on /Rotate'd pages.
 */
@RunWith(AndroidJUnit4::class)
class PdfProcessorsTest {

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
    // Compression                                                        //
    // ------------------------------------------------------------------ //

    @Test
    fun compress_shrinksImageHeavyPdf_andKeepsTextVector() = runBlocking<Unit> {
        val fixture = buildFixturePdf(pages = 3, photoOnFirstPage = true)
        try {
            val input = PickedFile(
                Uri.fromFile(fixture).toString(), fixture.name, fixture.length(), FileType.PDF
            )
            val result = PdfCompressor(context, storage)
                .compress(input, CompressionProfile.RECOMMENDED, "test_compress")
            val item = result.items.single().also { outputs.add(it) }

            assertTrue(
                "expected a real reduction, got ${item.outputSize} of ${item.originalSize}",
                item.outputSize in 1 until (item.originalSize * 6) / 10
            )

            stageToCache(item.uri).let { staged ->
                PDDocument.load(staged).use { doc ->
                    assertEquals(3, doc.numberOfPages)
                    val text = PDFTextStripper().getText(doc)
                    // Text must survive as real text — the old rasterizing
                    // pipeline destroyed it (that was the blurry output).
                    assertTrue(text.contains("NexCompress fixture page 1"))
                    assertTrue(text.contains("NexCompress fixture page 3"))
                }
                staged.delete()
            }

            // The recompressed photo must still render (e.g. its soft mask
            // survived) — mid-tone pixels where the photo was drawn.
            val rendered = renderFirstPage(item.uri)
            try {
                assertTrue(
                    "embedded photo went missing after compression",
                    midToneRatio(rendered, 0.15f, 0.36f, 0.66f, 0.34f) > 0.5f
                )
            } finally {
                rendered.recycle()
            }
        } finally {
            fixture.delete()
        }
    }

    @Test
    fun compress_alreadyOptimizedTextPdf_neverGrowsOrBlurs() = runBlocking<Unit> {
        val fixture = buildFixturePdf(pages = 2, photoOnFirstPage = false)
        try {
            val input = PickedFile(
                Uri.fromFile(fixture).toString(), fixture.name, fixture.length(), FileType.PDF
            )
            val result = PdfCompressor(context, storage)
                .compress(input, CompressionProfile.BALANCED, "test_textonly")
            val item = result.items.single().also { outputs.add(it) }

            assertTrue("output must never exceed the input", item.outputSize <= item.originalSize)
            stageToCache(item.uri).let { staged ->
                PDDocument.load(staged).use { doc ->
                    val text = PDFTextStripper().getText(doc)
                    assertTrue(text.contains("NexCompress fixture page 2"))
                }
                staged.delete()
            }
        } finally {
            fixture.delete()
        }
    }

    /**
     * The compression profiles must actually differ: a more aggressive profile
     * (lower quality + smaller image cap) has to produce a smaller file, else the
     * user's profile choice is meaningless.
     */
    @Test
    fun compress_moreAggressiveProfileYieldsSmallerFile() = runBlocking<Unit> {
        val fixture = buildFixturePdf(pages = 2, photoOnFirstPage = true)
        try {
            val input = PickedFile(
                Uri.fromFile(fixture).toString(), fixture.name, fixture.length(), FileType.PDF
            )
            val compressor = PdfCompressor(context, storage)
            val balanced = compressor.compress(input, CompressionProfile.BALANCED, "t_bal")
                .items.single().also { outputs.add(it) }
            val highFidelity = compressor.compress(input, CompressionProfile.HIGH_FIDELITY, "t_hi")
                .items.single().also { outputs.add(it) }

            assertTrue("both profiles should shrink the image-heavy fixture",
                balanced.outputSize < balanced.originalSize &&
                    highFidelity.outputSize < highFidelity.originalSize)
            assertTrue(
                "BALANCED (${balanced.outputSize}) must be smaller than " +
                    "HIGH_FIDELITY (${highFidelity.outputSize}) — profile knobs ignored?",
                balanced.outputSize < highFidelity.outputSize
            )
        } finally {
            fixture.delete()
        }
    }

    // ------------------------------------------------------------------ //
    // Fixtures & helpers                                                 //
    // ------------------------------------------------------------------ //

    /**
     * Letter-sized doc; with [photoOnFirstPage], page 1 carries a large noisy
     * photo that JPEGFactory wraps with an /SMask (alpha-flagged bitmap) and
     * page 2 a plain opaque one — covering both real-world image flavors.
     */
    private fun buildFixturePdf(pages: Int, photoOnFirstPage: Boolean): File {
        val doc = PDDocument()
        try {
            for (i in 0 until pages) {
                val page = PDPage(PDRectangle.LETTER)
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText()
                    cs.setFont(PDType1Font.HELVETICA, 14f)
                    cs.newLineAtOffset(72f, 720f)
                    cs.showText("NexCompress fixture page ${i + 1}")
                    cs.endText()
                    if (photoOnFirstPage && i <= 1) {
                        val photo = noisyPhoto(if (i == 0) 2400 else 1800, if (i == 0) 1700 else 1200)
                        try {
                            photo.setHasAlpha(i == 0)
                            val img = JPEGFactory.createFromImage(doc, photo, 0.92f)
                            cs.drawImage(img, 72f, 200f, 468f, 331f)
                        } finally {
                            photo.recycle()
                        }
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

    /** Gradient+noise photo — incompressible enough to dominate the file size. */
    private fun noisyPhoto(w: Int, h: Int): Bitmap {
        val rnd = Random(42)
        val px = IntArray(w * h)
        for (i in px.indices) {
            val x = i % w
            val y = i / w
            val r = (x * 255 / w + rnd.nextInt(64)).coerceIn(0, 255)
            val g = (y * 255 / h + rnd.nextInt(64)).coerceIn(0, 255)
            val b = (128 + rnd.nextInt(64)).coerceIn(0, 255)
            px[i] = Color.rgb(r, g, b)
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun stageToCache(uriString: String): File =
        PdfFiles.copyToCache(context, Uri.parse(uriString), "verify_")

    private fun renderFirstPage(uriString: String): Bitmap {
        val renderer = PdfPageRenderer(context, Uri.parse(uriString))
        try {
            return renderer.renderPage(0, 800) ?: error("could not render signed page")
        } finally {
            renderer.close()
        }
    }

    /** Fraction of sampled pixels in the region that are mid-tone (photo-like). */
    private fun midToneRatio(bmp: Bitmap, l: Float, t: Float, w: Float, h: Float): Float =
        sampleRatio(bmp, l, t, w, h) { c ->
            val lum = (Color.red(c) + Color.green(c) + Color.blue(c)) / 3
            lum in 30..225
        }

    private fun sampleRatio(
        bmp: Bitmap,
        l: Float,
        t: Float,
        w: Float,
        h: Float,
        predicate: (Int) -> Boolean
    ): Float {
        val x0 = (bmp.width * l).toInt().coerceIn(0, bmp.width - 1)
        val y0 = (bmp.height * t).toInt().coerceIn(0, bmp.height - 1)
        val x1 = (bmp.width * (l + w)).toInt().coerceIn(x0 + 1, bmp.width)
        val y1 = (bmp.height * (t + h)).toInt().coerceIn(y0 + 1, bmp.height)
        var hits = 0
        var total = 0
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                if (predicate(bmp.getPixel(x, y))) hits++
                total++
                x += 3
            }
            y += 3
        }
        return if (total == 0) 0f else hits.toFloat() / total
    }
}
