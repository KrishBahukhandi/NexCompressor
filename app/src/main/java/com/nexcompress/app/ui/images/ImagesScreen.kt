package com.nexcompress.app.ui.images

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import com.nexcompress.app.domain.model.ImageBatchItem
import com.nexcompress.app.domain.model.ImageEditSpec
import com.nexcompress.app.domain.model.ImageFormat
import com.nexcompress.app.domain.model.PickedFile
import com.nexcompress.app.domain.util.FormatUtils
import com.nexcompress.app.ui.CompressionViewModel
import com.nexcompress.app.ui.components.ImageThumbnail
import com.nexcompress.app.ui.components.SectionLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.calvin.reorderable.ReorderableColumn

/**
 * Unified Images tool — pick one or more images, optionally edit each
 * (rotate / flip / crop / resize), then export as converted image files
 * (JPG / PNG / WebP) or combine them into a single PDF. Everything runs
 * on-device. Replaces the former Convert Images, Images → PDF and Image
 * Studio screens.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImagesScreen(
    viewModel: CompressionViewModel,
    onBack: () -> Unit,
    onStartProcessing: () -> Unit
) {
    val context = LocalContext.current
    val items = viewModel.imageItems
    var editingIndex by remember { mutableStateOf<Int?>(null) }

    // Per-image editor takes over the screen while open.
    val editing = editingIndex?.let { items.getOrNull(it) }
    if (editing != null) {
        ImageEditorPanel(
            source = editing.source,
            initial = editing.editSpec,
            onCancel = { editingIndex = null },
            onApply = { spec ->
                viewModel.updateImageEditSpec(editingIndex!!, spec)
                editingIndex = null
            }
        )
        return
    }

    val asPdf = viewModel.imagesAsPdf
    val format = viewModel.selectedFormat
    val maxReached = items.size >= CompressionViewModel.MAX_IMAGE_SELECTION

    val addPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (!uris.isNullOrEmpty()) {
            uris.forEach { uri ->
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            }
            viewModel.onImagesPickedAppend(uris)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Images", style = MaterialTheme.typography.titleMedium) },
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionLabel("Selected images (${items.size}/${CompressionViewModel.MAX_IMAGE_SELECTION})")
                TextButton(
                    onClick = { runCatching { addPicker.launch(arrayOf("image/*")) } },
                    enabled = !maxReached
                ) {
                    Icon(Icons.Filled.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Add")
                }
            }

            if (items.isEmpty()) {
                Text(
                    "Reading images…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(8.dp)
                )
            } else {
                if (items.size > 1) {
                    Text(
                        "Drag the handle to reorder; tap the pencil to edit a photo.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                ReorderableColumn(
                    list = items,
                    onSettle = { from, to -> viewModel.moveImage(from, to) },
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) { index, item, isDragging ->
                    key(item.source.uriString) {
                        BatchRow(
                            item = item,
                            extension = if (asPdf) "pdf" else format.extension,
                            showExtension = !asPdf,
                            canRemove = items.size > 1,
                            dragging = isDragging,
                            dragHandle = {
                                IconButton(
                                    onClick = {},
                                    modifier = Modifier.draggableHandle().size(40.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.DragHandle,
                                        contentDescription = "Reorder",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            onRename = { viewModel.updateImageNameAt(index, it) },
                            onEdit = { editingIndex = index },
                            onRemove = { viewModel.removeImageAt(index) }
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            SectionLabel("Export as")
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = !asPdf,
                    onClick = { viewModel.updateImagesAsPdf(false) },
                    shape = SegmentedButtonDefaults.itemShape(0, 2)
                ) { Text("Image files") }
                SegmentedButton(
                    selected = asPdf,
                    onClick = { viewModel.updateImagesAsPdf(true) },
                    shape = SegmentedButtonDefaults.itemShape(1, 2)
                ) { Text("Single PDF") }
            }

            if (asPdf) {
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = viewModel.imagesToPdfName,
                    onValueChange = { viewModel.updateImagesToPdfName(it) },
                    singleLine = true,
                    label = { Text("PDF file name") },
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
                QualityRow(
                    quality = viewModel.imagesToPdfQuality,
                    lossless = false,
                    onChange = { viewModel.updateImagesToPdfQuality(it) }
                )
            } else {
                Spacer(Modifier.height(4.dp))
                SectionLabel("Output format")
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    ImageFormat.entries.forEachIndexed { index, fmt ->
                        SegmentedButton(
                            selected = fmt == format,
                            onClick = { viewModel.setFormat(fmt) },
                            shape = SegmentedButtonDefaults.itemShape(index, ImageFormat.entries.size)
                        ) { Text(fmt.displayName) }
                    }
                }
                QualityRow(
                    quality = viewModel.imageQuality,
                    lossless = format.lossless,
                    onChange = { viewModel.updateImageQuality(it) }
                )
            }

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    if (asPdf) viewModel.startImagesToPdf() else viewModel.startImageConversion()
                    onStartProcessing()
                },
                enabled = items.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(Icons.Filled.Bolt, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(8.dp))
                Text(
                    if (asPdf) "COMBINE INTO PDF"
                    else "CONVERT ${items.size} ${if (items.size == 1) "IMAGE" else "IMAGES"}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp).padding(top = 2.dp)
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    "Everything runs on-device. Your photos never leave this phone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun QualityRow(quality: Int, lossless: Boolean, onChange: (Int) -> Unit) {
    Spacer(Modifier.height(6.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        SectionLabel("Quality")
        Text(
            if (lossless) "Lossless" else "$quality%",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
    }
    Slider(
        value = quality.toFloat(),
        onValueChange = { onChange(it.toInt()) },
        valueRange = 10f..100f,
        enabled = !lossless,
        modifier = Modifier.fillMaxWidth()
    )
    if (lossless) {
        Text(
            "PNG is lossless — the quality scale isn't applied.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun BatchRow(
    item: ImageBatchItem,
    extension: String,
    showExtension: Boolean,
    canRemove: Boolean,
    dragging: Boolean,
    dragHandle: @Composable () -> Unit,
    onRename: (String) -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit
) {
    val elevation by animateDpAsState(if (dragging) 8.dp else 1.dp, label = "dragElevation")
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = elevation)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 2.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            dragHandle()
            Box {
                ImageThumbnail(
                    uriString = item.source.uriString,
                    modifier = Modifier.size(56.dp)
                )
                if (item.isEdited) {
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .size(18.dp)
                            .padding(1.dp)
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.Edit,
                            contentDescription = "Edited",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(11.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                OutlinedTextField(
                    value = item.outputName,
                    onValueChange = onRename,
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    textStyle = MaterialTheme.typography.bodyMedium,
                    trailingIcon = if (showExtension) {
                        {
                            Text(
                                ".$extension",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(end = 10.dp)
                            )
                        }
                    } else null,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "Original: ${FormatUtils.formatBytesCompact(item.source.sizeBytes)}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onEdit) {
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = "Edit image",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            IconButton(onClick = onRemove, enabled = canRemove) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Remove image",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ====================================================================== //
// Per-image editor (rotate / flip / crop / resize)                       //
// ====================================================================== //

private val RESIZE_OPTIONS: List<Pair<String, Int?>> = listOf(
    "Original" to null,
    "2048 px" to 2048,
    "1280 px" to 1280,
    "1024 px" to 1024,
    "640 px" to 640
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImageEditorPanel(
    source: PickedFile,
    initial: ImageEditSpec?,
    onApply: (ImageEditSpec) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    var rotation by remember { mutableIntStateOf(initial?.rotationDegrees ?: 0) }
    var flipH by remember { mutableStateOf(initial?.flipHorizontal ?: false) }
    var flipV by remember { mutableStateOf(initial?.flipVertical ?: false) }
    val initialCrop = initial?.crop ?: CropRect.FULL
    var cropEnabled by remember { mutableStateOf(initial?.crop?.isFull == false) }
    var cropL by remember { mutableFloatStateOf(initialCrop.left) }
    var cropT by remember { mutableFloatStateOf(initialCrop.top) }
    var cropR by remember { mutableFloatStateOf(initialCrop.right) }
    var cropB by remember { mutableFloatStateOf(initialCrop.bottom) }
    var maxLongEdge by remember { mutableStateOf(initial?.maxLongEdge) }

    // Reset the crop frame whenever orientation changes (image dimensions change).
    LaunchedEffect(rotation, flipH, flipV) {
        cropL = 0f; cropT = 0f; cropR = 1f; cropB = 1f
    }

    var previewFailed by remember(source.uriString, rotation, flipH, flipV) { mutableStateOf(false) }
    val preview by produceState<Bitmap?>(null, source.uriString, rotation, flipH, flipV) {
        value = withContext(Dispatchers.IO) {
            runCatching { loadPreview(context, Uri.parse(source.uriString), rotation, flipH, flipV) }.getOrNull()
        }
        previewFailed = value == null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit image", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Cancel")
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
            Box(
                modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp).padding(top = 8.dp),
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
                    Box(Modifier.fillMaxWidth().aspectRatio(aspect)) {
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

            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = RoundedCornerShape(14.dp)
                ) { Text("Cancel") }
                Button(
                    onClick = {
                        onApply(
                            ImageEditSpec(
                                rotationDegrees = rotation,
                                flipHorizontal = flipH,
                                flipVertical = flipV,
                                crop = if (cropEnabled) CropRect(cropL, cropT, cropR, cropB) else CropRect.FULL,
                                maxLongEdge = maxLongEdge
                            )
                        )
                    },
                    enabled = preview != null,
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) { Text("Apply", fontWeight = FontWeight.Bold) }
            }
            // Lets the user clear a previously-applied edit.
            TextButton(
                onClick = { onApply(ImageEditSpec()) },
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) { Text("Reset to original") }
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
