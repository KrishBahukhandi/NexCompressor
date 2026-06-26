package com.nexcompress.app.data.processor

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * On-device OCR over a rendered page bitmap, backed by Google ML Kit's bundled
 * Latin text-recognition model. Inference runs entirely on the device — the
 * model ships inside the APK, so nothing is uploaded.
 *
 * Returns recognized text grouped into blocks (≈ paragraphs); each line carries
 * its bounding-box height so the caller can estimate a font size for heading
 * detection.
 */
internal object OcrEngine {

    /** One recognized text line plus the pixel height of its bounding box. */
    data class OcrLine(val text: String, val heightPx: Int)

    /** One recognized word with its bounding box (source-bitmap pixels). */
    data class OcrWord(
        val text: String,
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int
    )

    /**
     * Recognized words with bounding boxes, for laying an invisible, selectable
     * text layer over a scanned page. Word-level (not line-level) so selection
     * and copy land on real word boundaries. Blocking — call off the main thread.
     */
    fun recognizeWords(bitmap: Bitmap): List<OcrWord> {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
            return result.textBlocks
                .flatMap { block -> block.lines }
                .flatMap { line -> line.elements }
                .mapNotNull { element ->
                    val box = element.boundingBox ?: return@mapNotNull null
                    val text = element.text.trim()
                    if (text.isEmpty()) null
                    else OcrWord(text, box.left, box.top, box.width(), box.height())
                }
        } finally {
            recognizer.close()
        }
    }

    /**
     * Recognizes text in [bitmap], returning non-empty blocks of lines in
     * reading order. Blocking — call off the main thread. The bitmap must stay
     * un-recycled until this returns.
     */
    fun recognizeBlocks(bitmap: Bitmap): List<List<OcrLine>> {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
            return result.textBlocks.mapNotNull { block ->
                val lines = block.lines.mapNotNull { line ->
                    val text = line.text.trim()
                    if (text.isEmpty()) null
                    else OcrLine(text, line.boundingBox?.height() ?: 0)
                }
                lines.ifEmpty { null }
            }
        } finally {
            recognizer.close()
        }
    }
}
