package com.nexcompress.app.data.processor

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import com.nexcompress.app.domain.model.FileType
import com.nexcompress.app.domain.model.PickedFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * Centralizes scoped-storage-compliant I/O (PRD §3.3):
 *  - API 29+  -> MediaStore Downloads/NexCompress (no runtime permission).
 *  - API 26–28 -> app-specific external Downloads dir (no runtime permission),
 *                 shared out via FileProvider.
 */
class FileStorageManager(private val context: Context) {

    data class SavedFile(val uri: Uri, val sizeBytes: Long)

    data class RenameResult(val displayName: String, val uriString: String)

    /** Resolves display name + byte size for a picked content URI. */
    fun resolveMetadata(uri: Uri, type: FileType): PickedFile {
        var name = defaultNameFor(type)
        var size = 0L
        try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0 && !cursor.isNull(nameIdx)) name = cursor.getString(nameIdx)
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) size = cursor.getLong(sizeIdx)
                }
            }
        } catch (_: Exception) {
            // Fall through to descriptor-based sizing below.
        }
        if (size <= 0L) {
            try {
                context.contentResolver.openFileDescriptor(uri, "r")?.use {
                    size = it.statSize.coerceAtLeast(0L)
                }
            } catch (_: Exception) { /* leave size as 0 */ }
        }
        return PickedFile(uri.toString(), name, size, type)
    }

    /**
     * Writes output through [writer] and returns its final URI + measured size.
     * The output lands in the public Downloads/NexCompress folder on modern OSes.
     */
    fun writeOutput(displayName: String, mimeType: String, writer: (OutputStream) -> Unit): SavedFile {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            writeViaMediaStore(displayName, mimeType, writer)
        } else {
            writeViaAppExternal(displayName, writer)
        }
    }

    // Only reached through the SDK_INT >= Q branch in writeOutput.
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun writeViaMediaStore(
        displayName: String,
        mimeType: String,
        writer: (OutputStream) -> Unit
    ): SavedFile {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val pending = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(
                MediaStore.Downloads.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/" + OUTPUT_DIR_NAME
            )
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, pending)
            ?: throw IOException("Could not create the output file in Downloads.")

        resolver.openOutputStream(uri)?.use(writer)
            ?: throw IOException("Could not open the output stream.")

        val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
        resolver.update(uri, done, null, null)

        return SavedFile(uri, queryMediaStoreSize(uri))
    }

    private fun writeViaAppExternal(displayName: String, writer: (OutputStream) -> Unit): SavedFile {
        val dir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            OUTPUT_DIR_NAME
        ).apply { if (!exists()) mkdirs() }

        val file = uniqueFile(dir, displayName)
        FileOutputStream(file).use(writer)

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        return SavedFile(uri, file.length())
    }

    private fun queryMediaStoreSize(uri: Uri): Long {
        return try {
            context.contentResolver.query(
                uri, arrayOf(OpenableColumns.SIZE), null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (idx >= 0 && !c.isNull(idx)) return c.getLong(idx)
                }
                0L
            } ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    /** Strips the extension from a filename: "thesis.pdf" -> "thesis". */
    fun baseNameOf(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        return if (dot > 0) fileName.substring(0, dot) else fileName
    }

    /**
     * Sanitizes a (possibly user-typed) base name and appends the extension, e.g.
     * ("My Report","pdf") -> "My Report.pdf". Illegal filename characters are
     * replaced; blank input falls back to "file".
     */
    fun composeOutputName(baseName: String, extension: String): String {
        val safe = baseName.replace(Regex("[^A-Za-z0-9-_ ]"), "_").trim().take(60).ifBlank { "file" }
        return "$safe.$extension"
    }

    /**
     * Renames an already-saved output file, preserving its extension.
     *  - MediaStore (API 29+): updates DISPLAY_NAME; the content URI is unchanged.
     *  - FileProvider (API 26–28): renames the backing file; URI is recomputed.
     * Returns null if the rename couldn't be applied (e.g. name collision).
     */
    fun renameOutput(uriString: String, currentDisplayName: String, newBaseName: String): RenameResult? {
        val ext = currentDisplayName.substringAfterLast('.', "")
        val newName = if (ext.isNotEmpty()) composeOutputName(newBaseName, ext) else newBaseName.trim()
        if (newName.isBlank() || newName == currentDisplayName) return null
        return try {
            val uri = Uri.parse(uriString)
            if (uri.authority == MediaStore.AUTHORITY) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, newName)
                }
                val updated = context.contentResolver.update(uri, values, null, null)
                if (updated > 0) RenameResult(newName, uriString) else null
            } else {
                renameLegacyFile(currentDisplayName, newName)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun renameLegacyFile(currentDisplayName: String, newDisplayName: String): RenameResult? {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), OUTPUT_DIR_NAME)
        val current = File(dir, currentDisplayName)
        if (!current.exists()) return null
        val target = uniqueFile(dir, newDisplayName)
        if (!current.renameTo(target)) return null
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
        return RenameResult(target.name, uri.toString())
    }

    private fun uniqueFile(dir: File, displayName: String): File {
        var candidate = File(dir, displayName)
        if (!candidate.exists()) return candidate
        val dot = displayName.lastIndexOf('.')
        val base = if (dot > 0) displayName.substring(0, dot) else displayName
        val ext = if (dot > 0) displayName.substring(dot) else ""
        var i = 1
        while (candidate.exists()) {
            candidate = File(dir, "$base($i)$ext")
            i++
        }
        return candidate
    }

    private fun defaultNameFor(type: FileType): String =
        if (type == FileType.PDF) "document.pdf" else "image.png"

    companion object {
        const val OUTPUT_DIR_NAME = "NexCompress"
    }
}
