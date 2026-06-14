package com.nexcompress.app.ui.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContract
import com.nexcompress.app.domain.model.OutputItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Download a copy" support: every output is already auto-saved to
 * Downloads/NexCompress, but these helpers let the user additionally save a
 * copy anywhere they choose via the Storage Access Framework — a single file
 * through a "Save as" dialog, or a whole batch into a picked folder.
 */
object FileSaver {

    data class CreateDocumentRequest(val suggestedName: String, val mimeType: String)

    /**
     * ACTION_CREATE_DOCUMENT with a per-launch MIME type (the stock
     * [androidx.activity.result.contract.ActivityResultContracts.CreateDocument]
     * fixes the MIME at construction, which doesn't fit mixed output types).
     */
    class CreateDocumentContract : ActivityResultContract<CreateDocumentRequest, Uri?>() {
        override fun createIntent(context: Context, input: CreateDocumentRequest): Intent =
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = input.mimeType
                putExtra(Intent.EXTRA_TITLE, input.suggestedName)
            }

        override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
            if (resultCode == Activity.RESULT_OK) intent?.data else null
    }

    /** Streams one saved output into a user-chosen document URI. */
    suspend fun copyToDocument(
        context: Context,
        sourceUriString: String,
        destination: Uri
    ): Boolean = withContext(Dispatchers.IO) {
        copyStream(context, sourceUriString, destination)
    }

    /**
     * Copies every output into the folder the user picked with
     * OpenDocumentTree. Returns how many files were saved; name collisions are
     * resolved by the destination provider (it appends a suffix).
     */
    suspend fun copyAllToTree(
        context: Context,
        items: List<OutputItem>,
        tree: Uri
    ): Int = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val parent = try {
            DocumentsContract.buildDocumentUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree)
            )
        } catch (e: Exception) {
            return@withContext 0
        }
        var saved = 0
        items.forEach { item ->
            try {
                val mime = IntentUtils.mimeTypeForName(item.displayName)
                val dest = DocumentsContract.createDocument(resolver, parent, mime, item.displayName)
                if (dest != null && copyStream(context, item.uri, dest)) saved++
            } catch (_: Exception) {
                // Skip this file; keep saving the rest of the batch.
            }
        }
        saved
    }

    private fun copyStream(context: Context, sourceUriString: String, destination: Uri): Boolean {
        return try {
            val resolver = context.contentResolver
            val input = resolver.openInputStream(Uri.parse(sourceUriString)) ?: return false
            val output = resolver.openOutputStream(destination, "w")
                ?: run { input.close(); return false }
            input.use { i -> output.use { o -> i.copyTo(o) } }
            true
        } catch (e: Exception) {
            false
        }
    }
}
