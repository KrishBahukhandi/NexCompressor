package com.nexcompress.app.data.processor

import android.graphics.Bitmap
import android.graphics.Matrix
import com.nexcompress.app.domain.model.CropRect
import com.nexcompress.app.domain.model.ImageEditSpec

/**
 * Shared geometric image edits — orient (rotate + flip) -> crop -> resize — in a
 * fixed order so the result matches the on-screen preview. Each step recycles the
 * input bitmap when it produces a new one, so callers pass a bitmap they own and
 * use only the returned bitmap afterwards.
 *
 * Used by the batch [ImageConverter] / [ImagesToPdfConverter] (which apply a
 * per-image [com.nexcompress.app.domain.model.ImageEditSpec]'s geometry but pick
 * format/quality once for the whole batch).
 */
internal object ImageTransforms {

    /** Applies orient -> crop -> resize from [spec]'s geometry (format/quality ignored). */
    fun applyGeometry(src: Bitmap, spec: ImageEditSpec): Bitmap {
        var b = orient(src, spec.rotationDegrees, spec.flipHorizontal, spec.flipVertical)
        b = crop(b, spec.crop)
        b = resize(b, spec.maxLongEdge)
        return b
    }

    fun orient(src: Bitmap, rotationDegrees: Int, flipHorizontal: Boolean, flipVertical: Boolean): Bitmap {
        val r = ((rotationDegrees % 360) + 360) % 360
        if (r == 0 && !flipHorizontal && !flipVertical) return src
        val m = Matrix()
        if (flipHorizontal || flipVertical) {
            m.postScale(if (flipHorizontal) -1f else 1f, if (flipVertical) -1f else 1f)
        }
        if (r != 0) m.postRotate(r.toFloat())
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        if (out !== src) src.recycle()
        return out
    }

    fun crop(src: Bitmap, crop: CropRect): Bitmap {
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

    fun resize(src: Bitmap, maxLongEdge: Int?): Bitmap {
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
}
