package com.nexcompress.app.data.processor

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.nexcompress.app.data.processor.OoxmlSupport.putPart
import com.nexcompress.app.domain.model.CompressionException
import com.nexcompress.app.domain.model.CompressionResult
import com.nexcompress.app.domain.model.FileType
import com.nexcompress.app.domain.model.OutputItem
import com.nexcompress.app.domain.model.PickedFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * PDF → PowerPoint (.pptx), fully on-device.
 *
 * Every PDF page is rendered at high resolution and becomes a full-bleed
 * picture slide, so the deck looks exactly like the source document. Slides
 * are images (not editable text) — by far the most faithful offline approach.
 */
class PdfToPptxConverter(
    private val context: Context,
    private val storage: FileStorageManager
) {

    suspend fun convert(input: PickedFile, outputBaseName: String): CompressionResult =
        withContext(Dispatchers.IO) {
            var renderer: PdfPageRenderer? = null
            val temp = File.createTempFile("nexpptx_", ".pptx", context.cacheDir)
            try {
                renderer = try {
                    PdfPageRenderer(context, Uri.parse(input.uriString))
                } catch (e: Exception) {
                    throw CompressionException(
                        "Couldn't read this PDF. It may be corrupted or password-protected."
                    )
                }
                val pageCount = renderer.pageCount
                if (pageCount <= 0) {
                    throw CompressionException("This PDF contains no readable pages.")
                }
                if (pageCount > MAX_PAGES) {
                    throw CompressionException(
                        "This PDF has $pageCount pages — the on-device limit is $MAX_PAGES slides."
                    )
                }

                // Stream each page's image + slide straight into the zip so only
                // one page bitmap is resident at a time (a 200-slide deck would
                // otherwise hold all its JPEGs in memory at once).
                ZipOutputStream(FileOutputStream(temp)).use { zip ->
                    writePptx(zip, renderer, pageCount)
                }

                val outName = storage.composeOutputName(outputBaseName, "pptx")
                val saved = storage.writeOutput(outName, PPTX_MIME) { os ->
                    temp.inputStream().use { it.copyTo(os) }
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
            } catch (e: CompressionException) {
                throw e
            } catch (oom: OutOfMemoryError) {
                throw CompressionException("This PDF is too large to convert on this device.")
            } catch (e: Exception) {
                throw CompressionException("Couldn't convert this PDF to a presentation.")
            } finally {
                runCatching { renderer?.close() }
                temp.delete()
            }
        }

    // ------------------------------------------------------------------ //
    // .pptx generation (streaming: one page bitmap resident at a time)    //
    // ------------------------------------------------------------------ //

    private suspend fun writePptx(z: ZipOutputStream, renderer: PdfPageRenderer, pageCount: Int) {
        // Deck size follows the first page's orientation (long edge = 10").
        var slideCx = 0L
        var slideCy = 0L

        for (i in 0 until pageCount) {
            coroutineContext.ensureActive()
            val bmp = renderer.renderPage(i, RENDER_LONG_EDGE)
                ?: throw CompressionException("Couldn't render page ${i + 1}.")
            val wPx: Int
            val hPx: Int
            val jpeg: ByteArray
            try {
                wPx = bmp.width
                hPx = bmp.height
                jpeg = ByteArrayOutputStream().use { baos ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos)
                    baos.toByteArray()
                }
            } finally {
                bmp.recycle()
            }
            if (i == 0) {
                if (hPx >= wPx) {
                    slideCy = SLIDE_LONG_EDGE_EMU
                    slideCx = SLIDE_LONG_EDGE_EMU * wPx / hPx
                } else {
                    slideCx = SLIDE_LONG_EDGE_EMU
                    slideCy = SLIDE_LONG_EDGE_EMU * hPx / wPx
                }
            }

            z.putPart("ppt/media/image${i + 1}.jpg", jpeg)

            // Letterbox the page image into the deck's slide rectangle.
            val scale = minOf(slideCx.toDouble() / wPx, slideCy.toDouble() / hPx)
            val extCx = (wPx * scale).toLong()
            val extCy = (hPx * scale).toLong()
            val offX = (slideCx - extCx) / 2
            val offY = (slideCy - extCy) / 2

            z.putPart(
                "ppt/slides/slide${i + 1}.xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
<p:cSld><p:spTree>
<p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>
<p:pic>
<p:nvPicPr><p:cNvPr id="2" name="Page ${i + 1}"/><p:cNvPicPr/><p:nvPr/></p:nvPicPr>
<p:blipFill><a:blip r:embed="rId2"/><a:stretch><a:fillRect/></a:stretch></p:blipFill>
<p:spPr><a:xfrm><a:off x="$offX" y="$offY"/><a:ext cx="$extCx" cy="$extCy"/></a:xfrm>
<a:prstGeom prst="rect"><a:avLst/></a:prstGeom></p:spPr>
</p:pic>
</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr>
</p:sld>"""
            )
            z.putPart(
                "ppt/slides/_rels/slide${i + 1}.xml.rels",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout" Target="../slideLayouts/slideLayout1.xml"/>
<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="../media/image${i + 1}.jpg"/>
</Relationships>"""
            )
        }

        // Package-level parts (order within the zip is irrelevant for OPC).
        run {
            val slideOverrides = (0 until pageCount).joinToString("") { i ->
                """<Override PartName="/ppt/slides/slide${i + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>"""
            }
            z.putPart(
                "[Content_Types].xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Default Extension="jpg" ContentType="image/jpeg"/>
<Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/>
<Override PartName="/ppt/slideMasters/slideMaster1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml"/>
<Override PartName="/ppt/slideLayouts/slideLayout1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml"/>
<Override PartName="/ppt/theme/theme1.xml" ContentType="application/vnd.openxmlformats-officedocument.theme+xml"/>
$slideOverrides
</Types>"""
            )
            z.putPart(
                "_rels/.rels",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="ppt/presentation.xml"/>
</Relationships>"""
            )

            val slideIds = (0 until pageCount).joinToString("") { i ->
                """<p:sldId id="${256 + i}" r:id="rId${2 + i}"/>"""
            }
            z.putPart(
                "ppt/presentation.xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:presentation xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
<p:sldMasterIdLst><p:sldMasterId id="2147483648" r:id="rId1"/></p:sldMasterIdLst>
<p:sldIdLst>$slideIds</p:sldIdLst>
<p:sldSz cx="$slideCx" cy="$slideCy"/><p:notesSz cx="6858000" cy="9144000"/>
</p:presentation>"""
            )
            val slideRels = (0 until pageCount).joinToString("") { i ->
                """<Relationship Id="rId${2 + i}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide${i + 1}.xml"/>"""
            }
            z.putPart(
                "ppt/_rels/presentation.xml.rels",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster" Target="slideMasters/slideMaster1.xml"/>
$slideRels
</Relationships>"""
            )

            z.putPart("ppt/slideMasters/slideMaster1.xml", SLIDE_MASTER_XML)
            z.putPart(
                "ppt/slideMasters/_rels/slideMaster1.xml.rels",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout" Target="../slideLayouts/slideLayout1.xml"/>
<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme" Target="../theme/theme1.xml"/>
</Relationships>"""
            )
            z.putPart("ppt/slideLayouts/slideLayout1.xml", SLIDE_LAYOUT_XML)
            z.putPart(
                "ppt/slideLayouts/_rels/slideLayout1.xml.rels",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster" Target="../slideMasters/slideMaster1.xml"/>
</Relationships>"""
            )
            z.putPart("ppt/theme/theme1.xml", THEME_XML)
        }
    }

    companion object {
        private const val RENDER_LONG_EDGE = 1920
        private const val JPEG_QUALITY = 85
        private const val MAX_PAGES = 200
        private const val SLIDE_LONG_EDGE_EMU = 9_144_000L // 10 inches
        private const val PPTX_MIME =
            "application/vnd.openxmlformats-officedocument.presentationml.presentation"

        private val SLIDE_MASTER_XML = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sldMaster xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
<p:cSld><p:spTree>
<p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>
</p:spTree></p:cSld>
<p:clrMap bg1="lt1" tx1="dk1" bg2="lt2" tx2="dk2" accent1="accent1" accent2="accent2" accent3="accent3" accent4="accent4" accent5="accent5" accent6="accent6" hlink="hlink" folHlink="folHlink"/>
<p:sldLayoutIdLst><p:sldLayoutId id="2147483649" r:id="rId1"/></p:sldLayoutIdLst>
</p:sldMaster>"""

        private val SLIDE_LAYOUT_XML = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sldLayout xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
<p:cSld name="Blank"><p:spTree>
<p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>
</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr>
</p:sldLayout>"""

        private val THEME_XML = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<a:theme xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" name="Office">
<a:themeElements>
<a:clrScheme name="Office">
<a:dk1><a:sysClr val="windowText" lastClr="000000"/></a:dk1>
<a:lt1><a:sysClr val="window" lastClr="FFFFFF"/></a:lt1>
<a:dk2><a:srgbClr val="44546A"/></a:dk2>
<a:lt2><a:srgbClr val="E7E6E6"/></a:lt2>
<a:accent1><a:srgbClr val="4472C4"/></a:accent1>
<a:accent2><a:srgbClr val="ED7D31"/></a:accent2>
<a:accent3><a:srgbClr val="A5A5A5"/></a:accent3>
<a:accent4><a:srgbClr val="FFC000"/></a:accent4>
<a:accent5><a:srgbClr val="5B9BD5"/></a:accent5>
<a:accent6><a:srgbClr val="70AD47"/></a:accent6>
<a:hlink><a:srgbClr val="0563C1"/></a:hlink>
<a:folHlink><a:srgbClr val="954F72"/></a:folHlink>
</a:clrScheme>
<a:fontScheme name="Office">
<a:majorFont><a:latin typeface="Calibri Light"/><a:ea typeface=""/><a:cs typeface=""/></a:majorFont>
<a:minorFont><a:latin typeface="Calibri"/><a:ea typeface=""/><a:cs typeface=""/></a:minorFont>
</a:fontScheme>
<a:fmtScheme name="Office">
<a:fillStyleLst><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:fillStyleLst>
<a:lnStyleLst><a:ln><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln><a:ln><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln><a:ln><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln></a:lnStyleLst>
<a:effectStyleLst><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst>
<a:bgFillStyleLst><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:bgFillStyleLst>
</a:fmtScheme>
</a:themeElements>
</a:theme>"""
    }
}
