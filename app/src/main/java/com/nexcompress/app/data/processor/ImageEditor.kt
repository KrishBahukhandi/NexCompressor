package com.nexcompress.app.data.processor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import com.nexcompress.app.domain.model.CompressionException
import com.nexcompress.app.domain.model.CompressionResult
import com.nexcompress.app.domain.model.CropRect
import com.nexcompress.app.domain.model.FileType
import com.nexcompress.app.domain.model.ImageEditSpec
import com.nexcompress.app.domain.model.ImageFormat
import com.nexcompress.app.domain.model.OutputItem
import com.nexcompress.app.domain.model.PickedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * On-device image studio: rotate / flip / crop / resize a single image, then
 * re-encode to JPG / PNG / WebP. Transforms run in a fixed order
 * (orient -> crop -> resize) so the result matches the on-screen preview.
 * Guarded against OOM and decode failures.
 */
class ImageEditor(
    private val context: Context,
    private val storage: FileStorageManager
) {

    suspend fun edit(
        source: PickedFile,
        spec: ImageEditSpec,
        outputBaseName: String
    ): CompressionResult = withContext(Dispatchers.IO) {
        val uri = Uri.parse(source.uriString)
        var current: Bitmap? = null
        var encoded: Bitmap? = null
        try {
            current = decodeSampled(uri)
                ?: throw CompressionException("Couldn't read that image. It may be corrupted or unsupported.")
            current = applyOrientation(current, spec)
            current = applyCrop(current, spec.crop)
            current = applyResize(current, spec.maxLongEdge)

            val format = spec.format
            // JPEG/WebP can't store transparency — flatten onto white first.
            encoded = if (!format.lossless && current.hasAlpha()) flattenOntoWhite(current) else current

            val outName = storage.composeOutputName(outputBaseName, format.extension)
            val quality = if (format.lossless) 100 else spec.quality
            val saved = storage.writeOutput(outName, format.mimeType) { os ->
                if (!encoded!!.compress(compressFormatFor(format), quality, os)) {
                    throw IOException("encode failed")
                }
            }

            CompressionResult(
                listOf(
                    OutputItem(
                        displayName = outName,
                        originalSize = source.sizeBytes,
                        outputSize = saved.sizeBytes,
                        uri = saved.uri.toString(),
                        type = FileType.IMAGE
                    )
                )
            )
        } catch (oom: OutOfMemoryError) {
            throw CompressionException("That image is too large to edit on this device.")
        } catch (e: CompressionException) {
            throw e
        } catch (e: Exception) {
            throw CompressionException("Couldn't process that image.")
        } finally {
            if (encoded != null && encoded !== current) encoded.recycle()
            current?.recycle()
        }
    }

    private fun applyOrientation(src: Bitmap, spec: ImageEditSpec): Bitmap {
        val r = ((spec.rotationDegrees % 360) + 360) % 360
        if (r == 0 && !spec.flipHorizontal && !spec.flipVertical) return src
        val m = Matrix()
        if (spec.flipHorizontal || spec.flipVertical) {
            m.postScale(if (spec.flipHorizontal) -1f else 1f, if (spec.flipVertical) -1f else 1f)
        }
        if (r != 0) m.postRotate(r.toFloat())
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        if (out !== src) src.recycle()
        return out
    }

    private fun applyCrop(src: Bitmap, crop: CropRect): Bitmap {
        if (crop.isFull) return src
        val w0 = src.width
        val h0 = src.height
        val x = (crop.left.coerceIn(0f, 1f) * w0).toInt().coerceIn(0, w0 - 1)
        val y = (crop.top.coerceIn(0f, 1f) * h0).toInt().coerceIn(0, h0 - 1)
        var cw = ((crop.right - crop.left).coerceIn(0f, 1f) * w0).toInt().coerceAtLeast(1)
        var ch = ((crop.bottom - crop.top).coerceIn(0f, 1f) * h0).toInt().coerceAtLeast(1)
        cw = cw.coerceAtMost(w0 - x)
        ch = ch.coerceAtMost(h0 - y)
        if (x == 0 && y == 0 && cw == w0 && ch == h0) return src
        val out = Bitmap.createBitmap(src, x, y, cw, ch)
        if (out !== src) src.recycle()
        return out
    }

    private fun applyResize(src: Bitmap, maxLongEdge: Int?): Bitmap {
        if (maxLongEdge == null) return src
        val longEdge = maxOf(src.width, src.height)
        if (longEdge <= maxLongEdge) return src
        val scale = maxLongEdge.toFloat() / longEdge
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        val out = Bitmap.createScaledBitmap(src, w, h, true)
        if (out !== src) src.recycle()
        return out
    }

    private fun decodeSampled(uri: Uri): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = resolver.openInputStream(uri) ?: return null
        boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }

        val options = BitmapFactory.Options().apply {
            inSampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    }

    private fun flattenOntoWhite(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.WHITE)
            drawBitmap(src, 0f, 0f, null)
        }
        return out
    }

    private fun computeInSampleSize(width: Int, height: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        var w = width
        var h = height
        while (w / 2 >= MAX_DECODE_DIMENSION || h / 2 >= MAX_DECODE_DIMENSION) {
            w /= 2; h /= 2; sample *= 2
        }
        return sample
    }

    private fun compressFormatFor(format: ImageFormat): Bitmap.CompressFormat = when (format) {
        ImageFormat.JPEG -> Bitmap.CompressFormat.JPEG
        ImageFormat.PNG -> Bitmap.CompressFormat.PNG
        ImageFormat.WEBP ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY
            else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
    }

    companion object {
        /** Longest-edge ceiling when first decoding (OOM safeguard before any user resize). */
        private const val MAX_DECODE_DIMENSION = 4096
    }
}
