package com.nexcompress.app.ui.share

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.IntentCompat

/**
 * A file (or files) handed to NexCompress from another app's share menu
 * (ACTION_SEND / SEND_MULTIPLE), already classified into the broad category we
 * route on.
 */
data class SharedInput(val uris: List<Uri>, val kind: Kind) {
    enum class Kind { IMAGE, PDF, TEXT }
}

/** Parses an incoming share intent, or returns null if it isn't a usable share. */
fun parseShareIntent(intent: Intent?, resolver: ContentResolver): SharedInput? {
    if (intent == null) return null
    val uris: List<Uri> = when (intent.action) {
        Intent.ACTION_SEND ->
            listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        Intent.ACTION_SEND_MULTIPLE ->
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?.filterNotNull().orEmpty()
        else -> return null
    }
    if (uris.isEmpty()) return null
    val kind = kindOf(intent.type) ?: kindOf(resolver.getType(uris.first())) ?: return null
    return SharedInput(uris, kind)
}

private fun kindOf(mime: String?): SharedInput.Kind? = when {
    mime == null -> null
    mime.startsWith("image/") -> SharedInput.Kind.IMAGE
    mime == "application/pdf" -> SharedInput.Kind.PDF
    mime.startsWith("text/") -> SharedInput.Kind.TEXT
    else -> null
}

/** One choice in the "what do you want to do with this PDF?" sheet. */
data class ShareAction(val label: String, val icon: ImageVector, val onClick: () -> Unit)

/**
 * Shown when a single PDF is shared in — a single PDF is ambiguous (compress?
 * sign? split?), so we let the user pick the tool instead of guessing.
 */
@Composable
fun SharePdfChooserDialog(
    actions: List<ShareAction>,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(vertical = 16.dp)) {
                Text(
                    "What do you want to do with this PDF?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                )
                Spacer(Modifier.size(8.dp))
                actions.forEach { action ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { action.onClick(); onDismiss() }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            action.icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.size(16.dp))
                        Text(action.label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Spacer(Modifier.size(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, modifier = Modifier.padding(end = 12.dp)) {
                        Text("Cancel")
                    }
                }
            }
        }
    }
}
