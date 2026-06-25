package com.nexcompress.app.domain.model

/**
 * A crop rectangle expressed as fractions (0..1) of the *displayed* image
 * (i.e. after rotation/flip), with the origin at the top-left. [FULL] means
 * "no crop". The same normalized rect is applied by [com.nexcompress.app.data.processor.ImageTransforms]
 * so what the user frames on screen is exactly what gets cut.
 */
data class CropRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val isFull: Boolean
        get() = left <= 0.001f && top <= 0.001f && right >= 0.999f && bottom >= 0.999f

    companion object {
        val FULL = CropRect(0f, 0f, 1f, 1f)
    }
}

/**
 * Declarative description of an on-device image edit. Its geometry is applied by
 * [com.nexcompress.app.data.processor.ImageTransforms] (used by the unified Images
 * tool via [com.nexcompress.app.data.processor.ImageConverter] /
 * [com.nexcompress.app.data.processor.ImagesToPdfConverter]).
 * Operations are applied in a fixed order: orient (rotate + flip) -> crop -> resize -> encode.
 */
data class ImageEditSpec(
    /** Clockwise rotation in degrees: 0 / 90 / 180 / 270. */
    val rotationDegrees: Int = 0,
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false,
    val crop: CropRect = CropRect.FULL,
    /** Longest-edge ceiling (px) applied after cropping; null keeps the size. */
    val maxLongEdge: Int? = null,
    val format: ImageFormat = ImageFormat.JPEG,
    val quality: Int = 80
)
