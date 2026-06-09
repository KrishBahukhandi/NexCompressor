package com.nexcompress.app.domain.model

/** Target transcode formats for the Image Conversion module (PRD §2.1). */
enum class ImageFormat(
    val displayName: String,
    val extension: String,
    val mimeType: String,
    /** Lossless formats ignore the quality slider. */
    val lossless: Boolean
) {
    WEBP("WebP", "webp", "image/webp", lossless = false),
    JPEG("JPEG", "jpg", "image/jpeg", lossless = false),
    PNG("PNG", "png", "image/png", lossless = true);

    companion object {
        val DEFAULT = WEBP
    }
}
