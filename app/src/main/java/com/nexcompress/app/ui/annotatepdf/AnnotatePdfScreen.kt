package com.nexcompress.app.ui.annotatepdf

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.TextFields
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.nexcompress.app.domain.model.AnnotationFont
import com.nexcompress.app.domain.model.ImageAnnotation
import com.nexcompress.app.domain.model.InkAnnotation
import com.nexcompress.app.domain.model.NormPoint
import com.nexcompress.app.domain.model.PdfAnnotation
import com.nexcompress.app.domain.model.TextAnnotation
import com.nexcompress.app.ui.CompressionViewModel
import com.nexcompress.app.ui.components.SectionLabel
import com.nexcompress.app.ui.components.SignaturePadDialog
import com.nexcompress.app.ui.components.rememberPdfRenderer
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class Tool { PEN, HIGHLIGHT, TEXT }

private val PEN_WIDTH_FRAC = 0.004f
private val HIGHLIGHT_WIDTH_FRAC = 0.03f
private const val TEXT_MAX_WIDTH_FRAC = 0.8f
private const val DEFAULT_FONT_PT = 16
private const val MIN_FONT_PT = 6
private const val MAX_FONT_PT = 96
/** Fallback page height (Letter, pt) used before the real page size loads. */
private const val FALLBACK_PAGE_PT = 792f

private val SWATCHES = listOf(
    Color(0xFF111827), // near-black
    Color(0xFFEF4444), // red
    Color(0xFF2563EB), // blue
    Color(0xFF16A34A), // green
    Color(0xFFF59E0B)  // amber
)

/** One placed annotation in the editor; text items keep an id so drags can update them. */
private sealed interface AnnItem { val pageIndex: Int }
private data class InkItem(val ink: InkAnnotation) : AnnItem { override val pageIndex get() = ink.pageIndex }
private data class TextItem(
    val id: Long,
    override val pageIndex: Int,
    val text: String,
    val left: Float,
    val top: Float,
    val fontFrac: Float,
    val colorArgb: Int,
    val font: AnnotationFont,
    val bold: Boolean
) : AnnItem
private data class ImageItem(
    val id: Long,
    override val pageIndex: Int,
    val png: ByteArray,
    val aspect: Float, // width / height of the image in px
    val left: Float,
    val top: Float,
    val widthFrac: Float
) : AnnItem

/** Compose font family for an annotation font. */
private fun AnnotationFont.toFontFamily(): FontFamily = when (this) {
    AnnotationFont.SANS -> FontFamily.SansSerif
    AnnotationFont.SERIF -> FontFamily.Serif
    AnnotationFont.MONO -> FontFamily.Monospace
}

/**
 * On-device PDF markup editor: add text boxes, draw with a pen, or highlight,
 * page by page. Placement is normalized over the displayed page, so it maps 1:1
 * onto the exported page ([com.nexcompress.app.data.processor.PdfAnnotator]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnnotatePdfScreen(
    viewModel: CompressionViewModel,
    onBack: () -> Unit,
    onStartProcessing: () -> Unit
) {
    val source = viewModel.annotateSource
    val rendererState = rememberPdfRenderer(source?.uriString)
    val renderer = rendererState.renderer
    val pageCount = renderer?.pageCount ?: 0

    var selectedPage by remember { mutableIntStateOf(0) }
    var tool by remember { mutableStateOf(Tool.PEN) }
    var color by remember { mutableStateOf(SWATCHES.first()) }
    val items = remember { mutableStateListOf<AnnItem>() }
    var nextId by remember { mutableStateOf(0L) }
    var pendingTextAt by remember { mutableStateOf<NormPoint?>(null) }
    var editingId by remember { mutableStateOf<Long?>(null) }
    var showSignPad by remember { mutableStateOf(false) }
    var pagePointHeight by remember { mutableIntStateOf(0) }

    val pagePreview by produceStatePreview(renderer, selectedPage)
    LaunchedEffect(renderer, selectedPage) {
        pagePointHeight = withContext(Dispatchers.IO) {
            renderer?.visualPointHeight(selectedPage) ?: 0
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Annotate PDF", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { if (items.isNotEmpty()) items.removeAt(items.lastIndex) },
                        enabled = items.isNotEmpty()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
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
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = { if (selectedPage > 0) selectedPage-- },
                        enabled = selectedPage > 0,
                        shape = RoundedCornerShape(12.dp)
                    ) { Icon(Icons.Filled.ChevronLeft, contentDescription = "Previous page") }
                    Text(
                        "Page ${selectedPage + 1} of $pageCount",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    OutlinedButton(
                        onClick = { if (selectedPage < pageCount - 1) selectedPage++ },
                        enabled = selectedPage < pageCount - 1,
                        shape = RoundedCornerShape(12.dp)
                    ) { Icon(Icons.Filled.ChevronRight, contentDescription = "Next page") }
                }
            }

            Box(
                modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp).padding(top = 4.dp),
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
                    AnnotationCanvas(
                        page = bmp,
                        aspect = aspect,
                        tool = tool,
                        color = color,
                        items = items,
                        selectedPage = selectedPage,
                        onAddInk = { points, highlighter ->
                            items.add(
                                InkItem(
                                    InkAnnotation(
                                        pageIndex = selectedPage,
                                        points = points,
                                        widthFrac = if (highlighter) HIGHLIGHT_WIDTH_FRAC else PEN_WIDTH_FRAC,
                                        colorArgb = color.toArgb(),
                                        highlighter = highlighter
                                    )
                                )
                            )
                        },
                        onTapForText = { at -> pendingTextAt = at },
                        onMoveText = { id, nl, nt ->
                            val idx = items.indexOfFirst { it is TextItem && it.id == id }
                            if (idx >= 0) {
                                val t = items[idx] as TextItem
                                items[idx] = t.copy(left = nl, top = nt)
                            }
                        },
                        onEditText = { id -> editingId = id },
                        onMoveImage = { id, nl, nt ->
                            val idx = items.indexOfFirst { it is ImageItem && it.id == id }
                            if (idx >= 0) {
                                val im = items[idx] as ImageItem
                                items[idx] = im.copy(left = nl, top = nt)
                            }
                        },
                        onResizeImage = { id, nw ->
                            val idx = items.indexOfFirst { it is ImageItem && it.id == id }
                            if (idx >= 0) {
                                val im = items[idx] as ImageItem
                                items[idx] = im.copy(widthFrac = nw)
                            }
                        },
                        onDeleteImage = { id ->
                            items.removeAll { it is ImageItem && it.id == id }
                        }
                    )
                }
            }

            // ---- Tools ----
            SectionLabel("Tool")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                ToolChip("Pen", Icons.Filled.Brush, tool == Tool.PEN, Modifier.weight(1f)) { tool = Tool.PEN }
                ToolChip("Highlight", Icons.Filled.Highlight, tool == Tool.HIGHLIGHT, Modifier.weight(1f)) { tool = Tool.HIGHLIGHT }
                ToolChip("Text", Icons.Filled.TextFields, tool == Tool.TEXT, Modifier.weight(1f)) { tool = Tool.TEXT }
            }

            SectionLabel("Color")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SWATCHES.forEach { sw ->
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .background(sw, CircleShape)
                            .border(
                                width = if (sw == color) 3.dp else 1.dp,
                                color = if (sw == color) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant,
                                shape = CircleShape
                            )
                            .pointerInput(Unit) { detectTapGestures { color = sw } }
                    )
                }
            }

            Text(
                when (tool) {
                    Tool.TEXT -> "Tap the page to add a text box. Tap a box to edit it, drag to move."
                    Tool.HIGHLIGHT -> "Swipe across the page to highlight."
                    Tool.PEN -> "Draw on the page with your finger."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedButton(
                onClick = { showSignPad = true },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Filled.Draw, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("Add signature")
            }

            OutlinedTextField(
                value = viewModel.annotateName,
                onValueChange = { viewModel.updateAnnotateName(it) },
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
                    viewModel.startAnnotate(items.map { it.toAnnotation() })
                    onStartProcessing()
                },
                enabled = source != null && items.isNotEmpty() && !rendererState.failed,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(Icons.Filled.Bolt, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(8.dp))
                Text("SAVE CHANGES", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            }
            if (items.isEmpty()) {
                Text(
                    "Add a note, drawing or highlight to enable saving.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    // Add-new text dialog.
    pendingTextAt?.let { at ->
        val ph = if (pagePointHeight > 0) pagePointHeight.toFloat() else FALLBACK_PAGE_PT
        TextStyleDialog(
            initial = TextDraft("", AnnotationFont.SANS, false, DEFAULT_FONT_PT / ph, color.toArgb()),
            isEdit = false,
            pagePointHeight = pagePointHeight,
            onConfirm = { draft ->
                items.add(
                    TextItem(
                        id = nextId++,
                        pageIndex = selectedPage,
                        text = draft.text,
                        left = at.x,
                        top = at.y,
                        fontFrac = draft.fontFrac,
                        colorArgb = draft.colorArgb,
                        font = draft.font,
                        bold = draft.bold
                    )
                )
                pendingTextAt = null
            },
            onDelete = {},
            onDismiss = { pendingTextAt = null }
        )
    }

    // Edit-existing text dialog (change text, font, size, color, or delete).
    editingId?.let { id ->
        val idx = items.indexOfFirst { it is TextItem && it.id == id }
        val item = items.getOrNull(idx) as? TextItem
        if (item == null) {
            editingId = null
        } else {
            TextStyleDialog(
                initial = TextDraft(item.text, item.font, item.bold, item.fontFrac, item.colorArgb),
                isEdit = true,
                pagePointHeight = pagePointHeight,
                onConfirm = { draft ->
                    items[idx] = item.copy(
                        text = draft.text,
                        font = draft.font,
                        bold = draft.bold,
                        fontFrac = draft.fontFrac,
                        colorArgb = draft.colorArgb
                    )
                    editingId = null
                },
                onDelete = {
                    items.removeAt(idx)
                    editingId = null
                },
                onDismiss = { editingId = null }
            )
        }
    }

    // Draw-a-signature pad (same component as Sign PDF); places a movable image.
    if (showSignPad) {
        SignaturePadDialog(
            onDismiss = { showSignPad = false },
            onDone = { png, aspect ->
                showSignPad = false
                items.add(
                    ImageItem(
                        id = nextId++,
                        pageIndex = selectedPage,
                        png = png,
                        aspect = aspect,
                        left = 0.30f,
                        top = 0.40f,
                        widthFrac = 0.40f
                    )
                )
            }
        )
    }
}

private fun AnnItem.toAnnotation(): PdfAnnotation = when (this) {
    is InkItem -> ink
    is TextItem -> TextAnnotation(
        pageIndex, text, left, top, fontFrac, colorArgb, font, bold, TEXT_MAX_WIDTH_FRAC
    )
    is ImageItem -> ImageAnnotation(pageIndex, png, left, top, widthFrac)
}

@Composable
private fun produceStatePreview(
    renderer: com.nexcompress.app.data.processor.PdfPageRenderer?,
    selectedPage: Int
): androidx.compose.runtime.State<Bitmap?> {
    val state = remember(renderer, selectedPage) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(renderer, selectedPage) {
        state.value = if (renderer == null) null else withContext(Dispatchers.IO) {
            runCatching { renderer.renderPage(selectedPage, 1400) }.getOrNull()
        }
    }
    return state
}

@Composable
private fun AnnotationCanvas(
    page: Bitmap,
    aspect: Float,
    tool: Tool,
    color: Color,
    items: List<AnnItem>,
    selectedPage: Int,
    onAddInk: (List<NormPoint>, Boolean) -> Unit,
    onTapForText: (NormPoint) -> Unit,
    onMoveText: (Long, Float, Float) -> Unit,
    onEditText: (Long) -> Unit,
    onMoveImage: (Long, Float, Float) -> Unit,
    onResizeImage: (Long, Float) -> Unit,
    onDeleteImage: (Long) -> Unit
) {
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    val current = remember { mutableStateListOf<Offset>() } // in-progress stroke (px)

    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(aspect)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
            .onSizeChanged { boxSize = it }
    ) {
        Image(
            bitmap = page.asImageBitmap(),
            contentDescription = "Page",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )

        // Ink layer: draws committed strokes for this page + the live stroke, and
        // captures drawing when the Pen/Highlight tools are active.
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(tool, selectedPage, color) {
                    if (tool == Tool.PEN || tool == Tool.HIGHLIGHT) {
                        detectDragGestures(
                            onDragStart = { pos -> current.clear(); current.add(pos) },
                            onDragEnd = {
                                val w = size.width.toFloat()
                                val h = size.height.toFloat()
                                if (current.size >= 1 && w > 0 && h > 0) {
                                    onAddInk(
                                        current.map { NormPoint(it.x / w, it.y / h) },
                                        tool == Tool.HIGHLIGHT
                                    )
                                }
                                current.clear()
                            },
                            onDragCancel = { current.clear() }
                        ) { change, _ ->
                            change.consume()
                            current.add(change.position)
                        }
                    }
                }
        ) {
            val w = size.width
            val h = size.height
            // Committed ink for this page.
            items.forEach { item ->
                if (item is InkItem && item.pageIndex == selectedPage) {
                    drawInk(item.ink, w, h)
                }
            }
            // Live stroke.
            if (current.size >= 2) {
                val path = Path().apply {
                    moveTo(current[0].x, current[0].y)
                    for (i in 1 until current.size) lineTo(current[i].x, current[i].y)
                }
                val strokePx = (if (tool == Tool.HIGHLIGHT) HIGHLIGHT_WIDTH_FRAC else PEN_WIDTH_FRAC) * w
                drawPath(
                    path = path,
                    color = if (tool == Tool.HIGHLIGHT) color.copy(alpha = 0.35f) else color,
                    style = Stroke(
                        width = strokePx.coerceAtLeast(1f),
                        cap = if (tool == Tool.HIGHLIGHT) StrokeCap.Square else StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )
            }
        }

        // Tap-to-place layer (only active for the Text tool, sits under text boxes).
        if (tool == Tool.TEXT) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(selectedPage) {
                        detectTapGestures { pos ->
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            if (w > 0 && h > 0) onTapForText(NormPoint(pos.x / w, pos.y / h))
                        }
                    }
            )
        }

        // Draggable boxes for this page (topmost): signatures, then text.
        if (boxSize.width > 0) {
            items.forEach { item ->
                if (item is ImageItem && item.pageIndex == selectedPage) {
                    ImageOverlayBox(item, boxSize, onMoveImage, onResizeImage, onDeleteImage)
                }
            }
            items.forEach { item ->
                if (item is TextItem && item.pageIndex == selectedPage) {
                    DraggableText(item, boxSize, onMoveText, onEditText)
                }
            }
        }
    }
}

@Composable
private fun ImageOverlayBox(
    item: ImageItem,
    boxSize: IntSize,
    onMove: (Long, Float, Float) -> Unit,
    onResize: (Long, Float) -> Unit,
    onDelete: (Long) -> Unit
) {
    val density = LocalDensity.current
    val bw = boxSize.width.toFloat()
    val bh = boxSize.height.toFloat()
    val placement by rememberUpdatedState(Triple(item.left, item.top, item.widthFrac))
    val imageBitmap = remember(item.png) {
        runCatching {
            android.graphics.BitmapFactory.decodeByteArray(item.png, 0, item.png.size).asImageBitmap()
        }.getOrNull()
    }
    val wPx = item.widthFrac * bw
    val hPx = wPx / item.aspect.coerceAtLeast(0.01f)
    Box(
        Modifier
            .offset { IntOffset((item.left * bw).roundToInt(), (item.top * bh).roundToInt()) }
            .size(with(density) { wPx.toDp() }, with(density) { hPx.toDp() })
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
            .pointerInput(item.id, boxSize) {
                detectDragGestures { change, drag ->
                    change.consume()
                    val (l, t, _) = placement
                    onMove(
                        item.id,
                        (l + drag.x / bw).coerceIn(0f, 1f),
                        (t + drag.y / bh).coerceIn(0f, 1f)
                    )
                }
            }
    ) {
        if (imageBitmap != null) {
            Image(
                bitmap = imageBitmap,
                contentDescription = "Signature",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }
        // Delete handle (top-start).
        Box(
            Modifier
                .align(Alignment.TopStart)
                .offset(x = (-10).dp, y = (-10).dp)
                .size(22.dp)
                .background(MaterialTheme.colorScheme.error, CircleShape)
                .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
                .pointerInput(item.id) { detectTapGestures { onDelete(item.id) } },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Remove",
                tint = MaterialTheme.colorScheme.onError,
                modifier = Modifier.size(14.dp)
            )
        }
        // Resize handle (bottom-end).
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 10.dp, y = 10.dp)
                .size(22.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape)
                .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
                .pointerInput(item.id, boxSize) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        val (l, _, w) = placement
                        val maxW = (1f - l).coerceAtLeast(0.08f)
                        onResize(item.id, (w + drag.x / bw).coerceIn(0.08f, maxW))
                    }
                }
        )
    }
}

@Composable
private fun DraggableText(
    item: TextItem,
    boxSize: IntSize,
    onMove: (Long, Float, Float) -> Unit,
    onEdit: (Long) -> Unit
) {
    val density = LocalDensity.current
    val bw = boxSize.width.toFloat()
    val bh = boxSize.height.toFloat()
    val placement by rememberUpdatedState(item.left to item.top)
    val fontSp = with(density) { (item.fontFrac * bh).toSp() }
    Box(
        Modifier
            .offset { IntOffset((item.left * bw).roundToInt(), (item.top * bh).roundToInt()) }
            // A border hint so users see the box is interactive (tap to edit, drag to move).
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
            .pointerInput(item.id) {
                detectTapGestures { onEdit(item.id) }
            }
            .pointerInput(item.id, boxSize) {
                detectDragGestures { change, drag ->
                    change.consume()
                    val (l, t) = placement
                    onMove(
                        item.id,
                        (l + drag.x / bw).coerceIn(0f, 1f),
                        (t + drag.y / bh).coerceIn(0f, 1f)
                    )
                }
            }
    ) {
        Text(
            text = item.text,
            color = Color(item.colorArgb),
            fontSize = fontSp,
            fontFamily = item.font.toFontFamily(),
            fontWeight = if (item.bold) FontWeight.Bold else FontWeight.Normal,
            // Wrap at the same fraction the engine uses, so paragraphs match.
            modifier = Modifier
                .widthIn(max = with(density) { (TEXT_MAX_WIDTH_FRAC * bw).toDp() })
                .padding(2.dp)
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawInk(
    ink: InkAnnotation,
    w: Float,
    h: Float
) {
    if (ink.points.isEmpty()) return
    val strokePx = (ink.widthFrac * w).coerceAtLeast(1f)
    val col = if (ink.highlighter) Color(ink.colorArgb).copy(alpha = 0.35f) else Color(ink.colorArgb)
    if (ink.points.size == 1) {
        val p = ink.points[0]
        drawCircle(col, radius = strokePx / 2f, center = Offset(p.x * w, p.y * h))
        return
    }
    val path = Path().apply {
        moveTo(ink.points[0].x * w, ink.points[0].y * h)
        for (i in 1 until ink.points.size) lineTo(ink.points[i].x * w, ink.points[i].y * h)
    }
    drawPath(
        path = path,
        color = col,
        style = Stroke(
            width = strokePx,
            cap = if (ink.highlighter) StrokeCap.Square else StrokeCap.Round,
            join = StrokeJoin.Round
        )
    )
}

@Composable
private fun ToolChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
        modifier = modifier
    )
}

/** Editable state of a text box while the dialog is open. */
private data class TextDraft(
    val text: String,
    val font: AnnotationFont,
    val bold: Boolean,
    val fontFrac: Float,
    val colorArgb: Int
)

@Composable
private fun TextStyleDialog(
    initial: TextDraft,
    isEdit: Boolean,
    pagePointHeight: Int,
    onConfirm: (TextDraft) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    val pageHeightPt = if (pagePointHeight > 0) pagePointHeight.toFloat() else FALLBACK_PAGE_PT
    var text by remember { mutableStateOf(initial.text) }
    var font by remember { mutableStateOf(initial.font) }
    var bold by remember { mutableStateOf(initial.bold) }
    var sizePt by remember {
        mutableStateOf((initial.fontFrac * pageHeightPt).roundToInt().coerceIn(MIN_FONT_PT, MAX_FONT_PT))
    }
    var colorArgb by remember { mutableStateOf(initial.colorArgb) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(18.dp).verticalScroll(rememberScrollState())) {
                Text(
                    if (isEdit) "Edit text" else "Add text",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Text") },
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = font.toFontFamily()),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(12.dp))
                Text("Font", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FontChip("Sans", FontFamily.SansSerif, font == AnnotationFont.SANS) { font = AnnotationFont.SANS }
                    FontChip("Serif", FontFamily.Serif, font == AnnotationFont.SERIF) { font = AnnotationFont.SERIF }
                    FontChip("Mono", FontFamily.Monospace, font == AnnotationFont.MONO) { font = AnnotationFont.MONO }
                }

                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Size", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.size(10.dp))
                    Text(
                        "$sizePt pt",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.weight(1f))
                    FilterChip(
                        selected = bold,
                        onClick = { bold = !bold },
                        label = { Text("Bold", fontWeight = FontWeight.Bold) }
                    )
                }
                Slider(
                    value = sizePt.toFloat(),
                    onValueChange = { sizePt = it.roundToInt() },
                    valueRange = MIN_FONT_PT.toFloat()..MAX_FONT_PT.toFloat(),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(12.dp))
                Text("Color", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SWATCHES.forEach { sw ->
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .background(sw, CircleShape)
                                .border(
                                    width = if (sw.toArgb() == colorArgb) 3.dp else 1.dp,
                                    color = if (sw.toArgb() == colorArgb) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outlineVariant,
                                    shape = CircleShape
                                )
                                .pointerInput(Unit) { detectTapGestures { colorArgb = sw.toArgb() } }
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (isEdit) {
                        TextButton(onClick = onDelete) {
                            Text("Delete", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.size(8.dp))
                    Button(
                        onClick = {
                            onConfirm(TextDraft(text, font, bold, sizePt / pageHeightPt, colorArgb))
                        },
                        enabled = text.isNotBlank()
                    ) { Text(if (isEdit) "Save" else "Add") }
                }
            }
        }
    }
}

@Composable
private fun FontChip(label: String, family: FontFamily, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontFamily = family) }
    )
}
