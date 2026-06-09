package com.nexcompress.app.data.processor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.util.Log
import com.nexcompress.app.domain.model.CompressionException
import com.nexcompress.app.domain.model.CompressionResult
import com.nexcompress.app.domain.model.FileType
import com.nexcompress.app.domain.model.ImageBatchItem
import com.nexcompress.app.domain.model.ImageFormat
import com.nexcompress.app.domain.model.OutputItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * Image Conversion module (PRD §2.1 / Flow B).
 *
 * Reads input PNG streams and transcodes them locally into WEBP (lossy) or JPEG
 * via [Bitmap.compress]. Batch jobs are processed sequentially on a background
 * dispatcher. Each image is decoded with bounds-aware down-sampling to keep
 * large pictures from exhausting the heap; per-file failures are isolated so one
 * bad image doesn't abort the whole batch.
 */
class ImageConverter(
    private val context: Context,
    private val storage: FileStorageManager
) {

    suspend fun convert(
        items: List<ImageBatchItem>,
        format: ImageFormat,
        quality: Int
    ): CompressionResult = withContext(Dispatchers.IO) {
        if (items.isEmpty()) {
            throw CompressionException("No images were selected for conversion.")
        }

        val produced = mutableListOf<OutputItem>()
        val failures = mutableListOf<String>()

        for (item in items) {
            coroutineContext.ensureActive() // cooperative cancellation
            val label = item.source.displayName
            try {
                produced += convertOne(item, format, quality)
            } catch (oom: OutOfMemoryError) {
                Log.w(TAG, "OOM converting $label")
                failures += label
            } catch (e: Exception) {
                Log.w(TAG, "Failed converting $label: ${e.message}", e)
                failures += label
            }
        }

        if (produced.isEmpty()) {
            throw CompressionException(
                "None of the selected images could be converted. They may be corrupted or unsupported."
            )
        }
        CompressionResult(produced)
    }

    private fun convertOne(item: ImageBatchItem, format: ImageFormat, quality: Int): OutputItem {
        val input = item.source
        val uri = Uri.parse(input.uriString)
        var source: Bitmap? = null
        var flattened: Bitmap? = null
        try {
            source = decodeSampled(uri)
                ?: throw CompressionException("Unable to decode ${input.displayName}.")

            // JPEG has no alpha channel — flatten transparency onto white first.
            val toEncode = if (format == ImageFormat.JPEG && source.hasAlpha()) {
                flattenOntoWhite(source).also { flattened = it }
            } else {
                source
            }

            val outName = storage.composeOutputName(item.outputName, format.extension)
            val saved = storage.writeOutput(outName, format.mimeType) { os ->
                val ok = toEncode.compress(compressFormatFor(format), quality, os)
                if (!ok) throw IOException("Encoder rejected ${input.displayName}.")
            }

            return OutputItem(
                displayName = outName,
                originalSize = input.sizeBytes,
                outputSize = saved.sizeBytes,
                uri = saved.uri.toString(),
                type = FileType.IMAGE
            )
        } finally {
            flattened?.recycle()
            source?.recycle()
        }
    }

    /** Two-pass decode: read bounds, then load down-sampled to cap memory use. */
    private fun decodeSampled(uri: Uri): Bitmap? {
        val resolver = context.contentResolver

        // Pass 1: bounds only. decodeStream returns null here by design, so the
        // null-check must be on the STREAM, not on the decode result.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = resolver.openInputStream(uri)
            ?: throw CompressionException("Could not open the selected image.")
        boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }

        // Pass 2: real decode at a memory-safe sample size.
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

    private fun compressFormatFor(format: ImageFormat): Bitmap.CompressFormat = when (format) {
        ImageFormat.JPEG -> Bitmap.CompressFormat.JPEG
        ImageFormat.PNG -> Bitmap.CompressFormat.PNG
        ImageFormat.WEBP ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }
    }

    private fun computeInSampleSize(width: Int, height: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        var w = width
        var h = height
        while (w / 2 >= MAX_DIMENSION || h / 2 >= MAX_DIMENSION) {
            w /= 2
            h /= 2
            sample *= 2
        }
        return sample
    }

    companion object {
        private const val TAG = "ImageConverter"

        /** Longest-edge ceiling before down-sampling kicks in (OOM safeguard). */
        private const val MAX_DIMENSION = 4096
    }
}
