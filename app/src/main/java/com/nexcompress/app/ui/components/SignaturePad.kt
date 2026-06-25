package com.nexcompress.app.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.Path as AndroidPath
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import java.io.ByteArrayOutputStream

/**
 * Finger-drawn signature pad. Shared by Sign PDF and the Annotate editor.
 * [onDone] returns a tightly-cropped transparent PNG plus its width/height
 * aspect ratio.
 */
@Composable
internal fun SignaturePadDialog(
    onDismiss: () -> Unit,
    onDone: (png: ByteArray, aspect: Float) -> Unit
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
    // This rasterizes on the main thread. Guard the allocation/draw so a low-RAM
    // OutOfMemoryError drops the signature gracefully (caller no-ops on null)
    // instead of crashing the app.
    val bmp = try {
        Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
    } catch (t: Throwable) {
        return null
    }
    return try {
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
        bytes to aspect
    } catch (t: Throwable) {
        null
    } finally {
        bmp.recycle()
    }
}
