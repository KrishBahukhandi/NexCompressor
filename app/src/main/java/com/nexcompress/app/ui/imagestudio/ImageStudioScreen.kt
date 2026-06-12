package com.nexcompress.app.ui.imagestudio

import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nexcompress.app.data.processor.ImageDecoding
import com.nexcompress.app.domain.model.CropRect
import com.nexcompress.app.domain.model.ImageEditSpec
import com.nexcompress.app.domain.model.ImageFormat
import com.nexcompress.app.ui.CompressionViewModel
import com.nexcompress.app.ui.components.SectionLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * On-device image studio: rotate, flip, crop and resize a single image, then
 * re-encode. The preview shows exactly what will be exported — the crop frame is
 * applied to the rotated image, matching [com.nexcompress.app.data.processor.ImageEditor].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageStudioScreen(
    viewModel: CompressionViewModel,
    onBack: () -> Unit,
    onStartProcessing: () -> Unit
) {
    val context = LocalContext.current
    val source = viewModel.imageEditSource

    var rotation by remember { mutableIntStateOf(0) }
    var flipH by remember { mutableStateOf(false) }
    var flipV by remember { mutableStateOf(false) }
    var cropEnabled by remember { mutableStateOf(false) }
    var cropL by remember { mutableFloatStateOf(0f) }
    var cropT by remember { mutableFloatStateOf(0f) }
    var cropR by remember { mutableFloatStateOf(1f) }
    var cropB by remember { mutableFloatStateOf(1f) }
    var maxLongEdge by remember { mutableStateOf<Int?>(null) }
    var format by remember { mutableStateOf(ImageFormat.JPEG) }
    var quality by remember { mutableIntStateOf(85) }

    // Reset the crop frame whenever orientation changes (image dimensions change).
    LaunchedEffect(rotation, flipH, flipV) {
        cropL = 0f; cropT = 0f; cropR = 1f; cropB = 1f
    }

    var previewFailed by remember(source?.uriString) { mutableStateOf(false) }
    val preview by produceState<Bitmap?>(null, source?.uriString, rotation, flipH, flipV) {
        val uri = source?.uriString
        // Decode + transform on IO; doing this on Main froze the UI on big photos.
        value = if (uri == null) null else withContext(Dispatchers.IO) {
            runCatching { loadPreview(context, Uri.parse(uri), rotation, flipH, flipV) }.getOrNull()
        }
        previewFailed = uri != null && value == null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Image Studio", style = MaterialTheme.typography.titleMedium) },
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
            // ---- Preview + crop overlay ----
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp)
                    .padding(top = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                val bmp = preview
                if (previewFailed) {
                    Text(
                        "Couldn't read this image. It may be corrupted or in an unsupported format.",
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
                    ) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "Preview",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                        if (cropEnabled) {
                            CropArea(
                                crop = CropRect(cropL, cropT, cropR, cropB),
                                onCropChange = { c -> cropL = c.left; cropT = c.top; cropR = c.right; cropB = c.bottom },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }

            // ---- Transform controls ----
            SectionLabel("Transform")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                ToolButton(Icons.Filled.RotateLeft, "Left", Modifier.weight(1f)) {
                    rotation = ((rotation - 90) % 360 + 360) % 360
                }
                ToolButton(Icons.Filled.RotateRight, "Right", Modifier.weight(1f)) {
                    rotation = (rotation + 90) % 360
                }
                ToolButton(Icons.Filled.Flip, "Flip H", Modifier.weight(1f)) { flipH = !flipH }
                ToolButton(Icons.Filled.Flip, "Flip V", Modifier.weight(1f)) { flipV = !flipV }
            }
            FilterChip(
                selected = cropEnabled,
                onClick = {
                    cropEnabled = !cropEnabled
                    if (cropEnabled) { cropL = 0.1f; cropT = 0.1f; cropR = 0.9f; cropB = 0.9f }
                },
                label = { Text("Crop") },
                leadingIcon = { Icon(Icons.Filled.Crop, contentDescription = null, modifier = Modifier.size(18.dp)) }
            )

            // ---- Resize ----
            Spacer(Modifier.height(2.dp))
            SectionLabel("Resize (longest edge)")
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RESIZE_OPTIONS.forEach { (label, value) ->
                    FilterChip(
                        selected = maxLongEdge == value,
                        onClick = { maxLongEdge = value },
                        label = { Text(label) }
                    )
                }
            }

            // ---- Output format ----
            Spacer(Modifier.height(2.dp))
            SectionLabel("Output format")
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                ImageFormat.entries.forEachIndexed { index, fmt ->
                    SegmentedButton(
                        selected = fmt == format,
                        onClick = { format = fmt },
                        shape = SegmentedButtonDefaults.itemShape(index, ImageFormat.entries.size)
                    ) { Text(fmt.displayName) }
                }
            }

            val lossless = format.lossless
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                SectionLabel("Quality")
                Text(
                    if (lossless) "Lossless" else "$quality%",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Slider(
                value = quality.toFloat(),
                onValueChange = { quality = it.toInt() },
                valueRange = 10f..100f,
                enabled = !lossless,
                modifier = Modifier.fillMaxWidth()
            )

            // ---- Output name ----
            OutlinedTextField(
                value = viewModel.imageEditName,
                onValueChange = { viewModel.updateImageEditName(it) },
                singleLine = true,
                label = { Text("Output file name") },
                shape = RoundedCornerShape(12.dp),
                trailingIcon = {
                    Text(
                        ".${format.extension}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 12.dp)
                    )
                },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    val spec = ImageEditSpec(
                        rotationDegrees = rotation,
                        flipHorizontal = flipH,
                        flipVertical = flipV,
                        crop = if (cropEnabled) CropRect(cropL, cropT, cropR, cropB) else CropRect.FULL,
                        maxLongEdge = maxLongEdge,
                        format = format,
                        quality = quality
                    )
                    viewModel.startImageEdit(spec)
                    onStartProcessing()
                },
                enabled = source != null && preview != null,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(Icons.Filled.Bolt, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(8.dp))
                Text("SAVE IMAGE", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            }
            Text(
                "Edited on-device and saved to Downloads/NexCompress.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ToolButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(52.dp),
        shape = RoundedCornerShape(14.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(18.dp))
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun CropArea(
    crop: CropRect,
    onCropChange: (CropRect) -> Unit,
    modifier: Modifier = Modifier
) {
    var size by remember { mutableStateOf(Size.Zero) }
    var active by remember { mutableIntStateOf(-1) }
    // The gesture coroutine outlives recompositions; reading the parameter
    // directly would freeze the frame at its gesture-start rectangle.
    val currentCrop by rememberUpdatedState(crop)
    Canvas(
        modifier
            .onSizeChanged { size = Size(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { pos -> active = hitTest(pos, currentCrop, size) },
                    onDragEnd = { active = -1 },
                    onDragCancel = { active = -1 }
                ) { change, drag ->
                    change.consume()
                    if (size.width <= 0f || size.height <= 0f) return@detectDragGestures
                    onCropChange(
                        updateCrop(currentCrop, active, drag.x / size.width, drag.y / size.height)
                    )
                }
            }
    ) {
        drawCropScrim(crop, size)
    }
}

private const val MIN_CROP = 0.08f

private fun hitTest(pos: Offset, crop: CropRect, size: Size): Int {
    if (size.width <= 0f) return -1
    val corners = listOf(
        Offset(crop.left * size.width, crop.top * size.height),
        Offset(crop.right * size.width, crop.top * size.height),
        Offset(crop.left * size.width, crop.bottom * size.height),
        Offset(crop.right * size.width, crop.bottom * size.height)
    )
    val threshold = 70f
    var best = -1
    var bestD = threshold
    corners.forEachIndexed { i, c ->
        val d = (c - pos).getDistance()
        if (d < bestD) { bestD = d; best = i }
    }
    if (best >= 0) return best
    val inside = pos.x in (crop.left * size.width)..(crop.right * size.width) &&
        pos.y in (crop.top * size.height)..(crop.bottom * size.height)
    return if (inside) 4 else -1
}

private fun updateCrop(crop: CropRect, active: Int, dx: Float, dy: Float): CropRect {
    var l = crop.left; var t = crop.top; var r = crop.right; var b = crop.bottom
    when (active) {
        0 -> { l += dx; t += dy }
        1 -> { r += dx; t += dy }
        2 -> { l += dx; b += dy }
        3 -> { r += dx; b += dy }
        4 -> {
            var nl = l + dx; var nt = t + dy; var nr = r + dx; var nb = b + dy
            if (nl < 0f) { nr -= nl; nl = 0f }
            if (nt < 0f) { nb -= nt; nt = 0f }
            if (nr > 1f) { nl -= (nr - 1f); nr = 1f }
            if (nb > 1f) { nt -= (nb - 1f); nb = 1f }
            return CropRect(nl.coerceIn(0f, 1f), nt.coerceIn(0f, 1f), nr.coerceIn(0f, 1f), nb.coerceIn(0f, 1f))
        }
        else -> return crop
    }
    l = l.coerceIn(0f, r - MIN_CROP)
    t = t.coerceIn(0f, b - MIN_CROP)
    r = r.coerceIn(l + MIN_CROP, 1f)
    b = b.coerceIn(t + MIN_CROP, 1f)
    return CropRect(l, t, r, b)
}

private fun DrawScope.drawCropScrim(crop: CropRect, size: Size) {
    if (size.width <= 0f) return
    val l = crop.left * size.width
    val t = crop.top * size.height
    val r = crop.right * size.width
    val b = crop.bottom * size.height
    val scrim = Color.Black.copy(alpha = 0.45f)
    drawRect(scrim, Offset(0f, 0f), Size(size.width, t))
    drawRect(scrim, Offset(0f, b), Size(size.width, size.height - b))
    drawRect(scrim, Offset(0f, t), Size(l, b - t))
    drawRect(scrim, Offset(r, t), Size(size.width - r, b - t))
    drawRect(Color.White, Offset(l, t), Size(r - l, b - t), style = Stroke(width = 3f))
    val hs = 22f
    listOf(Offset(l, t), Offset(r, t), Offset(l, b), Offset(r, b)).forEach { c ->
        drawRect(Color.White, Offset(c.x - hs / 2, c.y - hs / 2), Size(hs, hs))
    }
}

private fun loadPreview(
    context: android.content.Context,
    uri: Uri,
    rotation: Int,
    flipH: Boolean,
    flipV: Boolean
): Bitmap? {
    // EXIF-corrected decode, so the preview (and the crop frame drawn over it)
    // matches what ImageEditor exports.
    val decoded = ImageDecoding.decodeUpright(context, uri, PREVIEW_LONG_EDGE) ?: return null

    val r = ((rotation % 360) + 360) % 360
    if (r == 0 && !flipH && !flipV) return decoded
    val m = Matrix()
    if (flipH || flipV) m.postScale(if (flipH) -1f else 1f, if (flipV) -1f else 1f)
    if (r != 0) m.postRotate(r.toFloat())
    val out = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
    if (out !== decoded) decoded.recycle()
    return out
}

private const val PREVIEW_LONG_EDGE = 1280

private val RESIZE_OPTIONS: List<Pair<String, Int?>> = listOf(
    "Original" to null,
    "2048 px" to 2048,
    "1280 px" to 1280,
    "1024 px" to 1024,
    "640 px" to 640
)
