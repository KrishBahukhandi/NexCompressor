package com.nexcompress.app.data.processor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.nexcompress.app.domain.model.CompressionException
import com.nexcompress.app.domain.model.CompressionProfile
import com.nexcompress.app.domain.model.CompressionResult
import com.nexcompress.app.domain.model.FileType
import com.nexcompress.app.domain.model.OutputItem
import com.nexcompress.app.domain.model.PickedFile
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.IdentityHashMap
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * PDF Compression module (PRD §2.1).
 *
 * Primary strategy: open the document with PDFBox and re-encode only the
 * *embedded images* (down-sampled to the profile's edge cap, JPEG at the
 * profile quality). Text, fonts and vector content are copied untouched, so
 * pages stay razor sharp at any zoom — only the imagery gets lighter. An image
 * is only swapped when its re-encoded form is meaningfully smaller, and the
 * whole output is only kept when it beats the source file, so compression can
 * never make a document bigger or blurrier than what went in.
 *
 * Fallback strategy (only for documents PDFBox cannot parse): rasterize each
 * page via [PdfRenderer] at a legible resolution and repack as JPEG pages.
 */
class PdfCompressor(
    private val context: Context,
    private val storage: FileStorageManager
) {

    suspend fun compress(
        input: PickedFile,
        profile: CompressionProfile,
        outputBaseName: String
    ): CompressionResult =
        withContext(Dispatchers.IO) {
            val uri = Uri.parse(input.uriString)
            val staged = try {
                PdfFiles.copyToCache(context, uri, "compress_")
            } catch (e: SecurityException) {
                throw CompressionException("Permission to read this document was denied.")
            } catch (e: Exception) {
                throw CompressionException("Unable to open the selected document.")
            }
            val rebuilt = File.createTempFile("nexpdf_", ".pdf", context.cacheDir)
            try {
                val smartSucceeded = try {
                    recompressEmbeddedImages(staged, rebuilt, profile)
                    true
                } catch (e: InvalidPasswordException) {
                    throw CompressionException(
                        "This PDF is password-protected. Unlock it first via Protect PDF, then compress it."
                    )
                } catch (e: CompressionException) {
                    throw e
                } catch (oom: OutOfMemoryError) {
                    throw CompressionException(
                        "This document is too large to process on this device. " +
                            "Try the Balanced profile to reduce memory usage."
                    )
                } catch (e: Exception) {
                    false // PDFBox couldn't parse it; try the raster fallback below.
                }

                if (!smartSucceeded) {
                    try {
                        rasterizeDocument(staged, rebuilt, profile)
                    } catch (e: CompressionException) {
                        throw e
                    } catch (oom: OutOfMemoryError) {
                        throw CompressionException(
                            "This document is too large to process on this device. " +
                                "Try the Balanced profile to reduce memory usage."
                        )
                    } catch (e: Exception) {
                        throw CompressionException("The PDF appears to be corrupted or unreadable.")
                    }
                }

                val originalSize = if (input.sizeBytes > 0) input.sizeBytes else staged.length()
                val rebuiltSize = rebuilt.length()
                // Emit the rebuilt file only for a real (>2%) win; otherwise keep the
                // original bytes — some PDFs are simply already optimized, and a
                // marginal saving isn't worth any generation loss.
                val useRebuilt = rebuiltSize in 1 until (originalSize - originalSize / 50)

                val outName = storage.composeOutputName(outputBaseName, "pdf")
                val saved = storage.writeOutput(outName, "application/pdf") { os ->
                    (if (useRebuilt) rebuilt else staged).inputStream().use { it.copyTo(os) }
                }

                CompressionResult(
                    listOf(
                        OutputItem(
                            displayName = outName,
                            originalSize = originalSize,
                            outputSize = saved.sizeBytes,
                            uri = saved.uri.toString(),
                            type = FileType.PDF
                        )
                    )
                )
            } finally {
                rebuilt.delete()
                staged.delete()
            }
        }

    /**
     * Lossless-structure pass: walks every page's resources (including nested
     * form XObjects) and re-encodes raster images in place. Shared images are
     * processed once and every reference is repointed to the same replacement.
     */
    private suspend fun recompressEmbeddedImages(
        input: File,
        output: File,
        profile: CompressionProfile
    ) {
        PDDocument.load(input, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
            if (doc.isEncrypted) {
                throw CompressionException(
                    "This PDF is password-protected. Unlock it first via Protect PDF, then compress it."
                )
            }
            if (doc.numberOfPages <= 0) {
                throw CompressionException("This PDF contains no readable pages.")
            }
            val processed = IdentityHashMap<COSStream, PDImageXObject?>()
            val visitedForms = IdentityHashMap<COSStream, Boolean>()
            for (page in doc.pages) {
                coroutineContext.ensureActive() // cooperative cancellation
                shrinkResources(doc, page.resources, profile, processed, visitedForms)
            }
            if (profile != CompressionProfile.HIGH_FIDELITY) {
                // XMP metadata can be tens of KB and never affects rendering.
                doc.documentCatalog.cosObject.removeItem(COSName.METADATA)
            }
            FileOutputStream(output).use { doc.save(it) }
        }
    }

    private suspend fun shrinkResources(
        doc: PDDocument,
        resources: PDResources?,
        profile: CompressionProfile,
        processed: MutableMap<COSStream, PDImageXObject?>,
        visitedForms: MutableMap<COSStream, Boolean>
    ) {
        if (resources == null) return
        for (name in resources.xObjectNames) {
            coroutineContext.ensureActive()
            val xobj = try {
                resources.getXObject(name)
            } catch (e: Exception) {
                null
            } ?: continue
            when (xobj) {
                is PDImageXObject -> {
                    val key = xobj.cosObject
                    if (processed.containsKey(key)) {
                        processed[key]?.let { resources.put(name, it) }
                    } else {
                        val replacement = recompressImage(doc, xobj, profile)
                        processed[key] = replacement
                        if (replacement != null) {
                            // Mark the new stream too, so pages sharing this
                            // resources dict don't re-encode our own output.
                            processed[replacement.cosObject] = null
                            resources.put(name, replacement)
                        }
                    }
                }
                is PDFormXObject -> {
                    if (visitedForms.put(xobj.cosObject, true) == null) {
                        shrinkResources(doc, xobj.resources, profile, processed, visitedForms)
                    }
                }
            }
        }
    }

    /**
     * Re-encodes one image as a (possibly down-sampled) JPEG. Returns null when
     * the image must be left alone: stencils and color-key /Mask images encode
     * transparency in the sample values themselves (JPEG would corrupt it),
     * 1-bit scans are already tighter than JPEG can manage, and anything that
     * doesn't shrink by at least ~10% isn't worth a generation loss. A soft
     * mask (/SMask) is fine: it lives in its own stream and is re-attached to
     * the re-encoded image unchanged.
     */
    private fun recompressImage(
        doc: PDDocument,
        image: PDImageXObject,
        profile: CompressionProfile
    ): PDImageXObject? {
        val dict = image.cosObject
        if (image.isStencil || image.bitsPerComponent <= 1) return null
        if (dict.containsKey(COSName.MASK)) return null
        val softMask = dict.getItem(COSName.SMASK)

        val originalLen = dict.getInt(COSName.LENGTH, 0)
        if (originalLen in 1 until MIN_IMAGE_BYTES) return null

        var bmp: Bitmap? = try {
            image.image
        } catch (e: Exception) {
            null
        } catch (oom: OutOfMemoryError) {
            null
        } ?: return null

        try {
            var working = bmp!!
            val longest = max(working.width, working.height)
            if (longest <= 0) return null
            if (longest > profile.maxImageEdgePx) {
                val s = profile.maxImageEdgePx.toFloat() / longest
                val w = (working.width * s).roundToInt().coerceAtLeast(1)
                val h = (working.height * s).roundToInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(working, w, h, true)
                if (scaled !== working) {
                    working.recycle()
                    working = scaled
                    bmp = scaled
                }
            }
            val jpeg = ByteArrayOutputStream().use { baos ->
                // JPEG keeps only the color channels; transparency (if any)
                // comes back via the re-attached /SMask below.
                working.compress(Bitmap.CompressFormat.JPEG, profile.quality, baos)
                baos.toByteArray()
            }
            if (jpeg.isEmpty()) return null
            if (originalLen > 0 && jpeg.size >= originalLen - originalLen / 10) return null
            val replacement = JPEGFactory.createFromStream(doc, ByteArrayInputStream(jpeg))
            if (softMask != null) {
                replacement.cosObject.setItem(COSName.SMASK, softMask)
            }
            return replacement
        } catch (e: Exception) {
            return null
        } catch (oom: OutOfMemoryError) {
            return null
        } finally {
            bmp?.recycle()
        }
    }

    // ---------------------------------------------------------------------
    // Raster fallback — only for documents PDFBox cannot parse at all.
    // ---------------------------------------------------------------------

    private suspend fun rasterizeDocument(
        input: File,
        output: File,
        profile: CompressionProfile
    ) {
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        val document = PdfDocument()
        try {
            pfd = ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)
            val pageCount = renderer.pageCount
            if (pageCount <= 0) {
                throw CompressionException("This PDF contains no readable pages.")
            }
            for (index in 0 until pageCount) {
                coroutineContext.ensureActive()
                renderPageInto(renderer, document, index, profile)
            }
            FileOutputStream(output).use { document.writeTo(it) }
        } finally {
            runCatching { document.close() }
            runCatching { renderer?.close() }
            runCatching { pfd?.close() }
        }
    }

    /** Renders one source page and appends a recompressed copy to [document]. */
    private fun renderPageInto(
        renderer: PdfRenderer,
        document: PdfDocument,
        index: Int,
        profile: CompressionProfile
    ) {
        var page: PdfRenderer.Page? = null
        var rendered: Bitmap? = null
        var recompressed: Bitmap? = null
        try {
            page = renderer.openPage(index)

            // Render to the profile's long-edge target (≈135 DPI on A4 at the
            // default profile) — rasterizing point-for-point is what used to
            // make compressed pages blurry.
            val longest = max(page.width, page.height).coerceAtLeast(1)
            val scale = (profile.maxImageEdgePx.toFloat() / longest).coerceIn(0.3f, 3f)
            val width = (page.width * scale).toInt().coerceAtLeast(1)
            val height = (page.height * scale).toInt().coerceAtLeast(1)

            rendered = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            // Flatten onto white so transparent regions don't render as black.
            rendered.eraseColor(Color.WHITE)
            page.render(rendered, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
            page.close()
            page = null

            // Whole pages carry text, so don't go below a legible JPEG quality.
            val quality = profile.quality.coerceAtLeast(60)
            val jpegBytes = ByteArrayOutputStream().use { baos ->
                rendered.compress(Bitmap.CompressFormat.JPEG, quality, baos)
                baos.toByteArray()
            }
            rendered.recycle()
            rendered = null

            recompressed = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                ?: throw CompressionException("Failed to rebuild page ${index + 1}.")

            val pageInfo = PdfDocument.PageInfo
                .Builder(recompressed.width, recompressed.height, index)
                .create()
            val outPage = document.startPage(pageInfo)
            outPage.canvas.drawBitmap(recompressed, 0f, 0f, null)
            document.finishPage(outPage)
        } finally {
            runCatching { page?.close() }
            rendered?.recycle()
            recompressed?.recycle()
        }
    }

    companion object {
        /** Below this encoded size an image isn't worth re-encoding. */
        private const val MIN_IMAGE_BYTES = 10 * 1024
    }
}
