package com.nexcompress.app.data.processor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Build
import com.nexcompress.app.domain.model.CompressionException
import com.nexcompress.app.domain.model.CompressionResult
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
            current = ImageTransforms.applyGeometry(current, spec)

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

    /** Memory-capped decode with EXIF applied, so edits start from upright pixels
     *  exactly like the on-screen preview. */
    private fun decodeSampled(uri: Uri): Bitmap? =
        ImageDecoding.decodeUpright(context, uri, MAX_DECODE_DIMENSION)

    private fun flattenOntoWhite(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.WHITE)
            drawBitmap(src, 0f, 0f, null)
        }
        return out
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
