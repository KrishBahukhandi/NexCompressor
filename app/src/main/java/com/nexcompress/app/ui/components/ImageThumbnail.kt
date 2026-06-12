package com.nexcompress.app.ui.components

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nexcompress.app.data.processor.ImageDecoding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Decodes a small, down-sampled preview bitmap from a content URI off the main thread. */
@Composable
private fun rememberThumbnail(uriString: String, targetPx: Int = 160): ImageBitmap? {
    val context = LocalContext.current
    var thumbnail by remember(uriString) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(uriString) {
        thumbnail = withContext(Dispatchers.IO) {
            runCatching {
                // EXIF-aware decode so camera photos preview upright.
                ImageDecoding.decodeUpright(context, Uri.parse(uriString), targetPx)
                    ?.asImageBitmap()
            }.getOrNull()
        }
    }
    return thumbnail
}

/** Rounded image preview with a graceful placeholder while the bitmap loads / on failure. */
@Composable
fun ImageThumbnail(uriString: String, modifier: Modifier = Modifier) {
    val bitmap = rememberThumbnail(uriString)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize()
            )
        } else {
            Icon(
                Icons.Filled.Image,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
