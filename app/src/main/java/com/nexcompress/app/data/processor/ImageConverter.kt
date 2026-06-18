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
            var work = decodeSampled(uri)
                ?: throw CompressionException("Unable to decode ${input.displayName}.")
            source = work

            // Apply any per-image edit (rotate / flip / crop / resize) first.
            item.editSpec?.let {
                work = ImageTransforms.applyGeometry(work, it)
                source = work
            }

            // JPEG has no alpha channel — flatten transparency onto white first.
            val toEncode = if (format == ImageFormat.JPEG && work.hasAlpha()) {
                flattenOntoWhite(work).also { flattened = it }
            } else {
                work
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

    /** Memory-capped decode with EXIF orientation applied (upright pixels). */
    private fun decodeSampled(uri: Uri): Bitmap? =
        ImageDecoding.decodeUpright(context, uri, MAX_DIMENSION)

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

    companion object {
        private const val TAG = "ImageConverter"

        /** Longest-edge ceiling before down-sampling kicks in (OOM safeguard). */
        private const val MAX_DIMENSION = 4096
    }
}
