package com.nexcompress.app.ui.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.net.toUri

/** Helpers for the external Share / Preview intents triggered from Results & History. */
object IntentUtils {

    /** Fires a system Share sheet for one or more output files. */
    fun share(context: Context, uris: List<String>, mimeType: String) {
        if (uris.isEmpty()) return
        try {
            val parsed = uris.map { it.toUri() }
            val intent = if (parsed.size == 1) {
                Intent(Intent.ACTION_SEND).apply {
                    type = mimeType
                    putExtra(Intent.EXTRA_STREAM, parsed.first())
                }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = mimeType
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(parsed))
                }
            }
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(
                Intent.createChooser(intent, "Share via").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (e: Exception) {
            Toast.makeText(context, "Nothing available to share with.", Toast.LENGTH_SHORT).show()
        }
    }

    /** Opens an output file in an external viewer (Preview action). */
    fun view(context: Context, uriString: String, mimeType: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uriString.toUri(), mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "No app found to open this file.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Couldn't open this file.", Toast.LENGTH_SHORT).show()
        }
    }

    /** Best-effort deep-link to this app's Play Store listing (rating prompt). */
    fun openPlayStoreListing(context: Context) {
        val pkg = context.packageName.removeSuffix(".debug")
        val market = Intent(Intent.ACTION_VIEW, "market://details?id=$pkg".toUri())
        val web = Intent(
            Intent.ACTION_VIEW,
            "https://play.google.com/store/apps/details?id=$pkg".toUri()
        )
        try {
            context.startActivity(market.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            runCatching { context.startActivity(web.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    /** Opens an external URL (e.g. the hosted privacy policy) in a browser. */
    fun openUrl(context: Context, url: String) {
        if (url.isBlank()) return
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            Toast.makeText(context, "Couldn't open the link.", Toast.LENGTH_SHORT).show()
        }
    }

    fun mimeTypeFor(isPdf: Boolean): String = if (isPdf) "application/pdf" else "image/*"

    /** Maps an output filename's extension to a concrete MIME type for Share/View. */
    fun mimeTypeForName(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "pdf" -> "application/pdf"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "txt" -> "text/plain"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "ppt" -> "application/vnd.ms-powerpoint"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "xls" -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        else -> "*/*"
    }
}
