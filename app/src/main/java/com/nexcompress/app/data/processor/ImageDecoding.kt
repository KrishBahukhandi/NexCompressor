package com.nexcompress.app.data.processor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface

/**
 * Shared image decoding for every feature that ingests user photos.
 *
 * Camera photos store their rotation as EXIF metadata that [BitmapFactory]
 * ignores; re-encoding such a bitmap drops the metadata and ships a sideways
 * image. So every decode here is two-pass (bounds → down-sampled pixels, to cap
 * memory) followed by applying the source's EXIF orientation, returning pixels
 * that are upright no matter where they came from.
 */
internal object ImageDecoding {

    /**
     * Decodes [uri] with its longest edge capped near [maxEdge] and its EXIF
     * orientation applied. Returns null when the image can't be decoded.
     */
    fun decodeUpright(context: Context, uri: Uri, maxEdge: Int): Bitmap? {
        val resolver = context.contentResolver

        // Bounds pass: decodeStream returns null here BY DESIGN, so the
        // null-check must be on the stream, never on the decode result.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = resolver.openInputStream(uri) ?: return null
        boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxEdge)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null

        return applyExifOrientation(decoded, exifOrientation(context, uri))
    }

    /** The EXIF orientation tag of [uri], or [ExifInterface.ORIENTATION_NORMAL]. */
    fun exifOrientation(context: Context, uri: Uri): Int = try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } ?: ExifInterface.ORIENTATION_NORMAL
    } catch (e: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

    /** Bakes an EXIF orientation into the pixels; recycles [src] when transformed. */
    fun applyExifOrientation(src: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postScale(-1f, 1f); m.postRotate(270f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postScale(-1f, 1f); m.postRotate(90f) }
            else -> return src
        }
        val out = try {
            Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        } catch (oom: OutOfMemoryError) {
            return src // better a sideways image than a crash
        }
        if (out !== src) src.recycle()
        return out
    }

    fun sampleSize(width: Int, height: Int, maxEdge: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        var w = width
        var h = height
        while (w / 2 >= maxEdge || h / 2 >= maxEdge) {
            w /= 2; h /= 2; sample *= 2
        }
        return sample
    }
}
