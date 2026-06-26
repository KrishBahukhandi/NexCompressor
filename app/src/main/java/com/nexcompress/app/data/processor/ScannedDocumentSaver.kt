package com.nexcompress.app.data.processor

import android.content.Context
import android.net.Uri
import com.nexcompress.app.domain.model.CompressionException
import com.nexcompress.app.domain.model.CompressionResult
import com.nexcompress.app.domain.model.FileType
import com.nexcompress.app.domain.model.OutputItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Persists the multi-page PDF produced by the ML Kit document scanner into the
 * app's Downloads/NexCompress output, so a scan lands in the same place (and
 * history) as every other result. The scanner hands back a ready PDF in a
 * private cache URI with a transient read grant, so we just copy it to durable
 * storage right away.
 */
class ScannedDocumentSaver(
    private val context: Context,
    private val storage: FileStorageManager
) {
    suspend fun save(pdfUri: Uri, outputBaseName: String): CompressionResult =
        withContext(Dispatchers.IO) {
            val outName = storage.composeOutputName(outputBaseName, "pdf")
            val saved = storage.writeOutput(outName, "application/pdf") { os ->
                context.contentResolver.openInputStream(pdfUri)?.use { it.copyTo(os) }
                    ?: throw CompressionException("Couldn't read the scanned document.")
            }
            CompressionResult(
                listOf(
                    OutputItem(
                        displayName = outName,
                        originalSize = saved.sizeBytes,
                        outputSize = saved.sizeBytes,
                        uri = saved.uri.toString(),
                        type = FileType.PDF
                    )
                )
            )
        }

    /** Saves each scanned page as its own JPEG image (one output per page). */
    suspend fun saveImages(imageUris: List<Uri>, outputBaseName: String): CompressionResult =
        withContext(Dispatchers.IO) {
            if (imageUris.isEmpty()) throw CompressionException("The scan produced no pages.")
            val produced = ArrayList<OutputItem>()
            try {
                imageUris.forEachIndexed { index, uri ->
                    val label = if (imageUris.size > 1) "${outputBaseName}_page_${index + 1}"
                    else outputBaseName
                    val outName = storage.composeOutputName(label, "jpg")
                    val saved = storage.writeOutput(outName, "image/jpeg") { os ->
                        context.contentResolver.openInputStream(uri)?.use { it.copyTo(os) }
                            ?: throw CompressionException("Couldn't read a scanned page.")
                    }
                    produced += OutputItem(
                        displayName = outName,
                        originalSize = saved.sizeBytes,
                        outputSize = saved.sizeBytes,
                        uri = saved.uri.toString(),
                        type = FileType.IMAGE
                    )
                }
            } catch (c: CancellationException) {
                produced.forEach { storage.deleteOutput(it.uri) }
                throw c
            } catch (e: Exception) {
                produced.forEach { storage.deleteOutput(it.uri) }
                throw if (e is CompressionException) e
                else CompressionException("Couldn't save the scanned images.")
            }
            CompressionResult(produced)
        }
}
