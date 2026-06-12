package com.nexcompress.app

import android.content.Context
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nexcompress.app.data.processor.FileStorageManager
import com.nexcompress.app.data.processor.PdfFiles
import com.nexcompress.app.data.remote.RestConversionService
import com.nexcompress.app.domain.model.FileType
import com.nexcompress.app.domain.model.OnlineConversion
import com.nexcompress.app.domain.model.OutputItem
import com.nexcompress.app.domain.model.PickedFile
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end check of the REAL online conversion path (multipart upload ->
 * ConvertAPI-style service -> download/decode -> Downloads). Runs only when a
 * CONVERT_API_KEY is configured (see app/build.gradle.kts); skipped otherwise
 * so CI stays key-less and green.
 */
@RunWith(AndroidJUnit4::class)
class OnlineConversionTest {

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
    fun wordToPdf_realService_producesSearchablePdf() = runBlocking<Unit> {
        val service = RestConversionService(context, storage)
        assumeTrue("CONVERT_API_KEY not configured — skipping", service.isConfigured)

        val docx = buildMinimalDocx()
        try {
            val input = PickedFile(
                Uri.fromFile(docx).toString(), docx.name, docx.length(), FileType.DOCUMENT
            )
            val result = service.convert(input, OnlineConversion.WORD_TO_PDF)
            val item = result.items.single().also { outputs.add(it) }

            assertTrue("converted file is empty", item.outputSize > 0)
            assertTrue("output should be a PDF name", item.displayName.endsWith(".pdf"))

            val staged = PdfFiles.copyToCache(context, Uri.parse(item.uri), "verify_")
            try {
                PDDocument.load(staged).use { doc ->
                    assertTrue("PDF has no pages", doc.numberOfPages >= 1)
                    val text = PDFTextStripper().getText(doc)
                    assertTrue(
                        "document text was lost in conversion",
                        text.contains(MARKER)
                    )
                }
            } finally {
                staged.delete()
            }
        } finally {
            docx.delete()
        }
    }

    /**
     * Smallest well-formed .docx (an OPC zip with content types, root rels and
     * one-paragraph document.xml) that desktop Word/LibreOffice also accept.
     */
    private fun buildMinimalDocx(): File {
        val f = File(context.cacheDir, "fixture_${System.nanoTime()}.docx")
        ZipOutputStream(f.outputStream()).use { zip ->
            fun put(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put(
                "[Content_Types].xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
</Types>"""
            )
            put(
                "_rels/.rels",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>"""
            )
            put(
                "word/document.xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
<w:body><w:p><w:r><w:t>$MARKER</w:t></w:r></w:p></w:body>
</w:document>"""
            )
        }
        return f
    }

    companion object {
        private const val MARKER = "NexCompress online conversion test 12345"
    }
}
