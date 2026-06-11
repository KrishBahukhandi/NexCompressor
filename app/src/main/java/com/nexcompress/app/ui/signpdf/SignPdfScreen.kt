package com.nexcompress.app.ui.signpdf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.Path as AndroidPath
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.nexcompress.app.data.processor.PdfPageRenderer
import com.nexcompress.app.domain.model.SignaturePlacement
import com.nexcompress.app.ui.CompressionViewModel
import com.nexcompress.app.ui.components.PdfPageThumbnail
import com.nexcompress.app.ui.components.SectionLabel
import com.nexcompress.app.ui.components.rememberPdfRenderer
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Draw a signature and stamp it onto a PDF page. The placement on screen maps 1:1
 * onto the exported page; the signed page is flattened to an image while every
 * other page stays lossless ([com.nexcompress.app.data.processor.PdfSigner]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignPdfScreen(
    viewModel: CompressionViewModel,
    onBack: () -> Unit,
    onStartProcessing: () -> Unit
) {
    val source = viewModel.signSource
    val rendererState = rememberPdfRenderer(source?.uriString)
    val renderer = rendererState.renderer
    val pageCount = renderer?.pageCount ?: 0

    var selectedPage by remember { mutableIntStateOf(0) }
    var showPad by remember { mutableStateOf(false) }
    var signaturePng by remember { mutableStateOf<ByteArray?>(null) }
    var sigAspect by remember { mutableFloatStateOf(3f) }
    var sigL by remember { mutableFloatStateOf(0.30f) }
    var sigT by remember { mutableFloatStateOf(0.62f) }
    var sigW by remember { mutableFloatStateOf(0.40f) }

    val sigBitmap = remember(signaturePng) {
        signaturePng?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
    }

    var pagePreview by remember(renderer, selectedPage) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(renderer, selectedPage) {
        val r = renderer
        pagePreview = if (r == null) null else withContext(Dispatchers.IO) {
            runCatching { r.renderPage(selectedPage, 1200) }.getOrNull()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sign PDF", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (pageCount > 1) {
                SectionLabel("Page to sign (${selectedPage + 1} of $pageCount)")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(pageCount) { idx ->
                        Card(
                            onClick = { selectedPage = idx },
                            shape = RoundedCornerShape(10.dp),
                            border = if (idx == selectedPage) {
                                BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                            } else null,
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            PdfPageThumbnail(
                                renderer = renderer,
                                pageIndex = idx,
                                modifier = Modifier.size(54.dp, 72.dp).padding(3.dp),
                                longEdgePx = 150
                            )
                        }
                    }
                }
            }

            SectionLabel("Position your signature")
            Box(
                modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp).padding(top = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                val bmp = pagePreview
                if (rendererState.failed) {
                    Text(
                        "Couldn't read this PDF. It may be corrupted or password-protected " +
                            "(unlock it first via Protect PDF).",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(24.dp)
                    )
                } else if (bmp == null) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                } else {
                    val aspect = (bmp.width.toFloat() / bmp.height.toFloat()).coerceIn(0.2f, 5f)
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(aspect)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "Page",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                        if (sigBitmap != null) {
                            SignatureOverlay(
                                sigBitmap = sigBitmap,
                                sigAspect = sigAspect,
                                sigL = sigL,
                                sigT = sigT,
                                sigW = sigW,
                                onPlace = { nl, nt, nw -> sigL = nl; sigT = nt; sigW = nw }
                            )
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { showPad = true },
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Filled.Draw, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(if (signaturePng == null) "Draw signature" else "Redraw")
                }
                if (signaturePng != null) {
                    OutlinedButton(
                        onClick = {
                            sigW = (sigW - 0.05f).coerceIn(0.12f, 0.9f)
                            sigL = sigL.coerceIn(0f, 1f - sigW)
                        },
                        modifier = Modifier.height(50.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) { Icon(Icons.Filled.ZoomOut, contentDescription = "Smaller") }
                    OutlinedButton(
                        onClick = {
                            sigW = (sigW + 0.05f).coerceIn(0.12f, 0.9f)
                            sigL = sigL.coerceIn(0f, 1f - sigW)
                        },
                        modifier = Modifier.height(50.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) { Icon(Icons.Filled.ZoomIn, contentDescription = "Larger") }
                }
            }
            if (signaturePng != null) {
                Text(
                    "Drag the signature anywhere on the page; pinch or pull the corner dot to resize.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            OutlinedTextField(
                value = viewModel.signName,
                onValueChange = { viewModel.updateSignName(it) },
                singleLine = true,
                label = { Text("Output file name") },
                shape = RoundedCornerShape(12.dp),
                trailingIcon = {
                    Text(
                        ".pdf",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 12.dp)
                    )
                },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(6.dp))
            Button(
                onClick = {
                    val png = signaturePng ?: return@Button
                    val previewAspect = pagePreview?.let { it.width.toFloat() / it.height.toFloat() } ?: 1f
                    val sigH = (sigW * previewAspect / sigAspect).coerceIn(0.02f, 1f)
                    viewModel.startSign(
                        png,
                        SignaturePlacement(
                            pageIndex = selectedPage,
                            left = sigL,
                            top = sigT,
                            width = sigW,
                            height = sigH
                        )
                    )
                    onStartProcessing()
                },
                enabled = source != null && signaturePng != null && !rendererState.failed,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(Icons.Filled.Bolt, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(8.dp))
                Text("APPLY SIGNATURE", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            }
            if (signaturePng == null) {
                Text(
                    "Draw a signature to enable signing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (showPad) {
        SignaturePadDialog(
            onDismiss = { showPad = false },
            onDone = { bytes, aspect ->
                signaturePng = bytes
                sigAspect = aspect
                showPad = false
            }
        )
    }
}

/**
 * The movable/resizable signature box over the page preview. One finger drags it
 * anywhere on the page, a two-finger pinch resizes it around its centre, and the
 * corner dot resizes from the bottom-right.
 *
 * The gesture coroutines run for as long as the finger is down while the box
 * recomposes underneath them, so they must read the placement through
 * [rememberUpdatedState] — capturing the parameters directly would freeze the
 * box at its gesture-start position.
 */
@Composable
private fun SignatureOverlay(
    sigBitmap: Bitmap,
    sigAspect: Float,
    sigL: Float,
    sigT: Float,
    sigW: Float,
    onPlace: (left: Float, top: Float, width: Float) -> Unit
) {
    val density = LocalDensity.current
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    val placement by rememberUpdatedState(Triple(sigL, sigT, sigW))
    Box(Modifier.fillMaxSize().onSizeChanged { boxSize = it }) {
        if (boxSize.width > 0 && boxSize.height > 0) {
            val bw = boxSize.width.toFloat()
            val bh = boxSize.height.toFloat()
            val boxAspect = bw / bh
            fun heightFor(w: Float) = (w * boxAspect / sigAspect).coerceIn(0.02f, 1f)
            val sigH = heightFor(sigW)
            Box(
                Modifier
                    .offset { IntOffset((sigL * bw).roundToInt(), (sigT * bh).roundToInt()) }
                    .size(
                        width = with(density) { (sigW * bw).toDp() },
                        height = with(density) { (sigH * bh).toDp() }
                    )
                    .border(1.5.dp, MaterialTheme.colorScheme.primary)
                    .pointerInput(boxSize, sigAspect) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val (l, t, w) = placement
                            val newW = (w * zoom).coerceIn(MIN_SIG_W, MAX_SIG_W)
                            val newH = heightFor(newW)
                            // Keep the centre fixed while pinching, then apply the pan.
                            val nl = (l - (newW - w) / 2f + pan.x / bw)
                                .coerceIn(0f, (1f - newW).coerceAtLeast(0f))
                            val nt = (t - (newH - heightFor(w)) / 2f + pan.y / bh)
                                .coerceIn(0f, (1f - newH).coerceAtLeast(0f))
                            onPlace(nl, nt, newW)
                        }
                    }
            ) {
                Image(
                    bitmap = sigBitmap.asImageBitmap(),
                    contentDescription = "Signature",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(2.dp)
                )
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 8.dp, y = 8.dp)
                        .size(18.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                        .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
                        .pointerInput(boxSize, sigAspect) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                val (l, t, w) = placement
                                // Growing must keep the box on the page both ways.
                                val maxW = minOf(
                                    MAX_SIG_W,
                                    1f - l,
                                    (1f - t) * sigAspect / boxAspect
                                ).coerceAtLeast(MIN_SIG_W)
                                val newW = (w + drag.x / bw).coerceIn(MIN_SIG_W, maxW)
                                onPlace(l, t, newW)
                            }
                        }
                )
            }
        }
    }
}

private const val MIN_SIG_W = 0.12f
private const val MAX_SIG_W = 0.9f

@Composable
private fun SignaturePadDialog(
    onDismiss: () -> Unit,
    onDone: (ByteArray, Float) -> Unit
) {
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    var current by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var padSize by remember { mutableStateOf(IntSize.Zero) }
    var blue by remember { mutableStateOf(false) }
    val penColor = if (blue) Color(0xFF1A56DB) else Color(0xFF111827)

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(18.dp)) {
                Text("Draw your signature", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f)
                        .background(Color.White, RoundedCornerShape(12.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                        .onSizeChanged { padSize = it }
                        .pointerInput(padSize) {
                            detectDragGestures(
                                onDragStart = { pos -> current = listOf(normalize(pos, padSize)) },
                                onDragEnd = {
                                    if (current.size > 1) strokes.add(current)
                                    current = emptyList()
                                },
                                onDragCancel = { current = emptyList() }
                            ) { change, _ ->
                                change.consume()
                                current = current + normalize(change.position, padSize)
                            }
                        }
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        val w = size.width
                        val h = size.height
                        (strokes + listOf(current)).forEach { stroke ->
                            if (stroke.size > 1) {
                                val path = Path()
                                path.moveTo(stroke[0].x * w, stroke[0].y * h)
                                for (i in 1 until stroke.size) {
                                    path.lineTo(stroke[i].x * w, stroke[i].y * h)
                                }
                                drawPath(path, penColor, style = Stroke(width = 6f))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(selected = !blue, onClick = { blue = false }, label = { Text("Black") })
                    FilterChip(selected = blue, onClick = { blue = true }, label = { Text("Blue") })
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { strokes.clear(); current = emptyList() }) { Text("Clear") }
                }
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.size(8.dp))
                    Button(
                        onClick = {
                            val result = rasterizeSignature(strokes.toList(), penColor)
                            if (result != null) onDone(result.first, result.second)
                        },
                        enabled = strokes.isNotEmpty()
                    ) { Text("Done") }
                }
            }
        }
    }
}

private fun normalize(p: Offset, size: IntSize): Offset {
    if (size.width <= 0 || size.height <= 0) return Offset(0f, 0f)
    return Offset((p.x / size.width).coerceIn(0f, 1f), (p.y / size.height).coerceIn(0f, 1f))
}

/**
 * Renders the captured strokes (normalized over a 2:1 pad) into a tightly-cropped
 * transparent PNG and returns it with its width/height aspect ratio.
 */
private fun rasterizeSignature(strokes: List<List<Offset>>, penColor: Color): Pair<ByteArray, Float>? {
    val points = strokes.filter { it.size > 1 }
    if (points.isEmpty()) return null
    val padAspect = 2f // pad is twice as wide as tall; correct x into y-units

    var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
    points.forEach { stroke ->
        stroke.forEach { p ->
            val xs = p.x * padAspect
            if (xs < minX) minX = xs
            if (xs > maxX) maxX = xs
            if (p.y < minY) minY = p.y
            if (p.y > maxY) maxY = p.y
        }
    }
    val pad = 0.06f
    minX -= pad; maxX += pad; minY -= pad; maxY += pad
    val spanX = (maxX - minX).coerceAtLeast(0.05f)
    val spanY = (maxY - minY).coerceAtLeast(0.05f)
    val aspect = (spanX / spanY).coerceIn(0.2f, 8f)

    val outW = 1200
    val outH = (outW / aspect).toInt().coerceAtLeast(1)
    val bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bmp)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = penColor.toArgb()
        style = Paint.Style.STROKE
        strokeWidth = outW * 0.014f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    points.forEach { stroke ->
        val path = AndroidPath()
        stroke.forEachIndexed { i, p ->
            val xs = p.x * padAspect
            val px = (xs - minX) / spanX * outW
            val py = (p.y - minY) / spanY * outH
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        canvas.drawPath(path, paint)
    }
    val bytes = ByteArrayOutputStream().use { baos ->
        bmp.compress(Bitmap.CompressFormat.PNG, 100, baos)
        baos.toByteArray()
    }
    bmp.recycle()
    return bytes to aspect
}
