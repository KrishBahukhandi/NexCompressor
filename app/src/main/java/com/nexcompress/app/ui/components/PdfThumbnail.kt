package com.nexcompress.app.ui.components

import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nexcompress.app.data.processor.PdfPageRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Opens a single [PdfPageRenderer] for [uriString], staging the document off the
 * main thread, and tears it down when the composition leaves. Share the returned
 * renderer across all page thumbnails on a screen (renders are serialized).
 */
@Composable
fun rememberPdfRenderer(uriString: String): State<PdfPageRenderer?> {
    val context = LocalContext.current
    val state = remember(uriString) { mutableStateOf<PdfPageRenderer?>(null) }
    DisposableEffect(uriString) {
        var created: PdfPageRenderer? = null
        val scope = CoroutineScope(Dispatchers.IO)
        scope.launch {
            val r = runCatching { PdfPageRenderer(context, Uri.parse(uriString)) }.getOrNull()
            created = r
            withContext(Dispatchers.Main) { state.value = r }
        }
        onDispose {
            scope.cancel()
            created?.close()
            state.value = null
        }
    }
    return state
}

/**
 * Renders one PDF page (upright) into a thumbnail, applying any extra user
 * [rotation] off the main thread. Shows a small spinner until ready.
 */
@Composable
fun PdfPageThumbnail(
    renderer: PdfPageRenderer?,
    pageIndex: Int,
    modifier: Modifier = Modifier,
    rotation: Int = 0,
    longEdgePx: Int = 260
) {
    val bitmap by produceState<Bitmap?>(null, renderer, pageIndex, rotation, longEdgePx) {
        value = if (renderer == null) null else withContext(Dispatchers.IO) {
            runCatching {
                val base = renderer.renderPage(pageIndex, longEdgePx) ?: return@runCatching null
                if (((rotation % 360) + 360) % 360 == 0) {
                    base
                } else {
                    val m = Matrix().apply { postRotate(rotation.toFloat()) }
                    val rotated = Bitmap.createBitmap(base, 0, 0, base.width, base.height, m, true)
                    if (rotated !== base) base.recycle()
                    rotated
                }
            }.getOrNull()
        }
    }

    Box(modifier = modifier.background(Color.White), contentAlignment = Alignment.Center) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        }
    }
}
