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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nexcompress.app.data.processor.PdfPageRenderer
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lifecycle of the document renderer behind a screen: null renderer + !failed
 * while the document is being staged, a usable renderer on success, or
 * [failed] = true when the PDF can't be opened (corrupted / password-protected).
 */
data class PdfRendererState(
    val renderer: PdfPageRenderer? = null,
    val failed: Boolean = false
)

/**
 * Opens a single [PdfPageRenderer] for [uriString], staging the document off the
 * main thread, and tears it down when the composition leaves — including when
 * disposal happens while the open is still in flight. Share the returned
 * renderer across all page thumbnails on a screen (renders are serialized).
 */
@Composable
fun rememberPdfRenderer(uriString: String?): PdfRendererState {
    val context = LocalContext.current
    var state by remember(uriString) { mutableStateOf(PdfRendererState()) }
    DisposableEffect(uriString) {
        val disposed = AtomicBoolean(false)
        if (!uriString.isNullOrBlank()) {
            // No scope.cancel() on dispose: the open must run to completion so a
            // late-arriving renderer can be closed instead of leaking its temp file.
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                val r = runCatching { PdfPageRenderer(context, Uri.parse(uriString)) }.getOrNull()
                withContext(Dispatchers.Main) {
                    if (disposed.get()) {
                        r?.close()
                    } else {
                        state = PdfRendererState(renderer = r, failed = r == null)
                    }
                }
            }
        }
        onDispose {
            disposed.set(true)
            state.renderer?.close()
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
