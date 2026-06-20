package com.nexcompress.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nexcompress.app.data.processor.FileStorageManager
import com.nexcompress.app.data.processor.ImageConverter
import com.nexcompress.app.data.processor.ImageEditor
import com.nexcompress.app.data.processor.ImagesToPdfConverter
import com.nexcompress.app.data.processor.PdfFiles
import com.nexcompress.app.data.processor.PdfMerger
import com.nexcompress.app.data.processor.PdfPageEditor
import com.nexcompress.app.data.processor.PdfProtector
import com.nexcompress.app.data.processor.PdfSplitter
import com.nexcompress.app.domain.model.CropRect
import com.nexcompress.app.domain.model.FileType
import com.nexcompress.app.domain.model.ImageBatchItem
import com.nexcompress.app.domain.model.ImageEditSpec
import com.nexcompress.app.domain.model.ImageFormat
import com.nexcompress.app.domain.model.OutputItem
import com.nexcompress.app.domain.model.PdfPageOp
import com.nexcompress.app.domain.model.PickedFile
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.util.Random
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device checks for the remaining processors: image conversion/editing with
 * EXIF-rotated camera photos, images→PDF size behavior, and the lossless PDF
 * utilities (merge / split / protect / page editor).
 */
@RunWith(AndroidJUnit4::class)
class UtilityProcessorsTest {

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
    // Images                                                             //
    // ------------------------------------------------------------------ //

    @Test
    fun imageConverter_appliesExifRotation() = runBlocking<Unit> {
        val jpeg = makeJpeg(width = 320, height = 160, exifRotate90 = true)
        try {
            val result = ImageConverter(context, storage).convert(
                listOf(ImageBatchItem(pickedImage(jpeg), "test_exif")),
                ImageFormat.PNG,
                quality = 95
            )
            val item = result.items.single().also { outputs.add(it) }

            val bmp = decodeOutput(item.uri)
            try {
                // EXIF said rotate 90°: a landscape source must come out portrait.
                assertEquals(160, bmp.width)
                assertEquals(320, bmp.height)
            } finally {
                bmp.recycle()
            }
        } finally {
            jpeg.delete()
        }
    }

    @Test
    fun imageEditor_cropsTheSelectedRegion() = runBlocking<Unit> {
        val jpeg = makeHalfRedHalfBlueJpeg(width = 400, height = 200)
        try {
            val result = ImageEditor(context, storage).edit(
                pickedImage(jpeg),
                ImageEditSpec(
                    crop = CropRect(0.5f, 0f, 1f, 1f), // keep the blue right half
                    format = ImageFormat.PNG,
                    quality = 100
                ),
                "test_crop"
            )
            val item = result.items.single().also { outputs.add(it) }

            val bmp = decodeOutput(item.uri)
            try {
                assertTrue("width should be halved", bmp.width in 190..210)
                val c = bmp.getPixel(bmp.width / 2, bmp.height / 2)
                assertTrue("cropped region should be blue", Color.blue(c) > 180 && Color.red(c) < 80)
            } finally {
                bmp.recycle()
            }
        } finally {
            jpeg.delete()
        }
    }

    @Test
    fun imagesToPdf_uprightPages_andJpegSizedOutput() = runBlocking<Unit> {
        val rotated = makeJpeg(width = 800, height = 600, exifRotate90 = true, noisy = true)
        val plain = makeJpeg(width = 800, height = 600, exifRotate90 = false, noisy = true)
        try {
            val result = ImagesToPdfConverter(context, storage).convert(
                listOf(
                    ImageBatchItem(pickedImage(rotated), "p1"),
                    ImageBatchItem(pickedImage(plain), "p2")
                ),
                "test_img2pdf",
                quality = 60
            )
            val item = result.items.single().also { outputs.add(it) }

            // JPEG-embedded output stays in the same ballpark as the JPEGs
            // themselves — the old flate path produced megabytes here.
            assertTrue(
                "PDF unexpectedly large (${item.outputSize} bytes) — images not JPEG-embedded?",
                item.outputSize in 1 until 600_000
            )

            val staged = stage(item.uri)
            try {
                PDDocument.load(staged).use { doc ->
                    assertEquals(2, doc.numberOfPages)
                    val p1 = doc.getPage(0).mediaBox
                    // EXIF rotation must be baked in: landscape source → portrait page.
                    assertTrue("EXIF rotation not applied to page 1", p1.height > p1.width)
                    val p2 = doc.getPage(1).mediaBox
                    assertTrue(p2.width > p2.height)
                    // Images are fitted onto standard A4 pages (595 x 842 pt),
                    // in the orientation of each image — not the raw pixel size.
                    assertEquals("page 1 should be A4 portrait width", 595f, p1.width, 1f)
                    assertEquals("page 1 should be A4 portrait height", 842f, p1.height, 1f)
                    assertEquals("page 2 should be A4 landscape width", 842f, p2.width, 1f)
                    assertEquals("page 2 should be A4 landscape height", 595f, p2.height, 1f)
                }
            } finally {
                staged.delete()
            }
        } finally {
            rotated.delete()
            plain.delete()
        }
    }

    // ------------------------------------------------------------------ //
    // PDF utilities                                                      //
    // ------------------------------------------------------------------ //

    @Test
    fun merger_concatenatesAllPages() = runBlocking<Unit> {
        val a = makeTextPdf(listOf("alpha one", "alpha two"))
        val b = makeTextPdf(listOf("beta one", "beta two", "beta three"))
        try {
            val item = PdfMerger(context, storage)
                .merge(listOf(pickedPdf(a), pickedPdf(b)), "test_merge")
                .items.single().also { outputs.add(it) }

            val staged = stage(item.uri)
            try {
                PDDocument.load(staged).use { doc ->
                    assertEquals(5, doc.numberOfPages)
                    val text = PDFTextStripper().getText(doc)
                    assertTrue(text.contains("alpha two"))
                    assertTrue(text.contains("beta three"))
                }
            } finally {
                staged.delete()
            }
        } finally {
            a.delete(); b.delete()
        }
    }

    @Test
    fun splitter_extractsChosenPages_andBurstsAll() = runBlocking<Unit> {
        val pdf = makeTextPdf(listOf("first page", "second page", "third page"))
        try {
            val splitter = PdfSplitter(context, storage)

            val extracted = splitter.extract(pickedPdf(pdf), listOf(2), "test_extract")
                .items.single().also { outputs.add(it) }
            stage(extracted.uri).let { staged ->
                PDDocument.load(staged).use { doc ->
                    assertEquals(1, doc.numberOfPages)
                    assertTrue(PDFTextStripper().getText(doc).contains("third page"))
                }
                staged.delete()
            }

            val burst = splitter.splitEach(pickedPdf(pdf), "test_burst").items
            burst.forEach { outputs.add(it) }
            assertEquals(3, burst.size)
            // Each burst file must hold exactly its own page, in order — not the
            // same page three times or a mis-ordered set.
            val expected = listOf("first page", "second page", "third page")
            burst.forEachIndexed { i, out ->
                val staged = stage(out.uri)
                try {
                    PDDocument.load(staged).use { doc ->
                        assertEquals("burst file ${i + 1} should be a single page", 1, doc.numberOfPages)
                        val text = PDFTextStripper().getText(doc)
                        assertTrue(
                            "burst file ${i + 1} should contain '${expected[i]}' but was [$text]",
                            text.contains(expected[i])
                        )
                    }
                } finally {
                    staged.delete()
                }
            }
        } finally {
            pdf.delete()
        }
    }

    @Test
    fun protector_locks_thenUnlocks() = runBlocking<Unit> {
        val pdf = makeTextPdf(listOf("classified content"))
        try {
            val protector = PdfProtector(context, storage)
            val locked = protector.protect(pickedPdf(pdf), "pw123", "test_lock")
                .items.single().also { outputs.add(it) }

            stage(locked.uri).let { staged ->
                try {
                    PDDocument.load(staged).use { /* should not open */ }
                    fail("locked PDF opened without a password")
                } catch (expected: Exception) {
                    // InvalidPasswordException is what viewers will hit — good.
                }
                PDDocument.load(staged, "pw123").use { doc ->
                    assertTrue(doc.isEncrypted)
                }
                staged.delete()
            }

            val unlocked = protector.unlock(
                PickedFile(locked.uri, locked.displayName, locked.outputSize, FileType.PDF),
                "pw123",
                "test_unlock"
            ).items.single().also { outputs.add(it) }
            stage(unlocked.uri).let { staged ->
                PDDocument.load(staged).use { doc ->
                    assertTrue(!doc.isEncrypted)
                    assertTrue(PDFTextStripper().getText(doc).contains("classified content"))
                }
                staged.delete()
            }
        } finally {
            pdf.delete()
        }
    }

    @Test
    fun pageEditor_reordersAndRotates() = runBlocking<Unit> {
        val pdf = makeTextPdf(listOf("page Alpha", "page Bravo"))
        try {
            val item = PdfPageEditor(context, storage).apply(
                pickedPdf(pdf),
                listOf(
                    PdfPageOp(sourceIndex = 1, rotation = 90),
                    PdfPageOp(sourceIndex = 0, rotation = 0)
                ),
                "test_pages"
            ).items.single().also { outputs.add(it) }

            val staged = stage(item.uri)
            try {
                PDDocument.load(staged).use { doc ->
                    assertEquals(2, doc.numberOfPages)
                    assertEquals(90, doc.getPage(0).rotation)
                    // Rotation makes the stripper break the text into vertical
                    // runs, so compare whitespace-insensitively.
                    val first = PDFTextStripper()
                        .apply { startPage = 1; endPage = 1 }
                        .getText(doc)
                        .filterNot { it.isWhitespace() }
                    assertTrue("pages were not reordered", first.contains("pageBravo"))
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

    private fun pickedImage(f: File) =
        PickedFile(Uri.fromFile(f).toString(), f.name, f.length(), FileType.IMAGE)

    private fun pickedPdf(f: File) =
        PickedFile(Uri.fromFile(f).toString(), f.name, f.length(), FileType.PDF)

    private fun stage(uriString: String): File =
        PdfFiles.copyToCache(context, Uri.parse(uriString), "verify_")

    private fun decodeOutput(uriString: String): Bitmap =
        context.contentResolver.openInputStream(Uri.parse(uriString))!!.use {
            BitmapFactory.decodeStream(it)!!
        }

    private fun makeJpeg(
        width: Int,
        height: Int,
        exifRotate90: Boolean,
        noisy: Boolean = false
    ): File {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        if (noisy) {
            val rnd = Random(7)
            val px = IntArray(width * height) {
                Color.rgb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256))
            }
            bmp.setPixels(px, 0, width, 0, 0, width, height)
        } else {
            bmp.eraseColor(Color.rgb(180, 40, 40))
        }
        val f = File(context.cacheDir, "img_${System.nanoTime()}.jpg")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bmp.recycle()
        if (exifRotate90) {
            ExifInterface(f.absolutePath).apply {
                setAttribute(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_ROTATE_90.toString()
                )
                saveAttributes()
            }
        }
        return f
    }

    private fun makeHalfRedHalfBlueJpeg(width: Int, height: Int): File {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint()
        paint.color = Color.rgb(220, 0, 0)
        canvas.drawRect(0f, 0f, width / 2f, height.toFloat(), paint)
        paint.color = Color.rgb(0, 0, 220)
        canvas.drawRect(width / 2f, 0f, width.toFloat(), height.toFloat(), paint)
        val f = File(context.cacheDir, "img_${System.nanoTime()}.jpg")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bmp.recycle()
        return f
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
                    cs.newLineAtOffset(72f, 700f)
                    cs.showText(text)
                    cs.endText()
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
