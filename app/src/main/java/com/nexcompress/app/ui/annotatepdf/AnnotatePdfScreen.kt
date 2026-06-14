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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.nexcompress.app.domain.model.InkAnnotation
import com.nexcompress.app.domain.model.NormPoint
import com.nexcompress.app.domain.model.PdfAnnotation
import com.nexcompress.app.domain.model.TextAnnotation
import com.nexcompress.app.ui.CompressionViewModel
import com.nexcompress.app.ui.components.SectionLabel
import com.nexcompress.app.ui.components.rememberPdfRenderer
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class Tool { PEN, HIGHLIGHT, TEXT }

private val PEN_WIDTH_FRAC = 0.004f
private val HIGHLIGHT_WIDTH_FRAC = 0.03f
private val TEXT_FONT_FRAC = 0.035f

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
    val colorArgb: Int
) : AnnItem

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

    val pagePreview by produceStatePreview(renderer, selectedPage)

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
                    Tool.TEXT -> "Tap the page to add a text box. Drag a box to move it."
                    Tool.HIGHLIGHT -> "Swipe across the page to highlight."
                    Tool.PEN -> "Draw on the page with your finger."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

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

    pendingTextAt?.let { at ->
        TextEntryDialog(
            onDismiss = { pendingTextAt = null },
            onConfirm = { entered ->
                if (entered.isNotBlank()) {
                    items.add(
                        TextItem(
                            id = nextId++,
                            pageIndex = selectedPage,
                            text = entered,
                            left = at.x,
                            top = at.y,
                            fontFrac = TEXT_FONT_FRAC,
                            colorArgb = color.toArgb()
                        )
                    )
                }
                pendingTextAt = null
            }
        )
    }
}

private fun AnnItem.toAnnotation(): PdfAnnotation = when (this) {
    is InkItem -> ink
    is TextItem -> TextAnnotation(pageIndex, text, left, top, fontFrac, colorArgb)
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
    onMoveText: (Long, Float, Float) -> Unit
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

        // Draggable text boxes for this page (topmost).
        if (boxSize.width > 0) {
            items.forEach { item ->
                if (item is TextItem && item.pageIndex == selectedPage) {
                    DraggableText(item, boxSize, onMoveText)
                }
            }
        }
    }
}

@Composable
private fun DraggableText(
    item: TextItem,
    boxSize: IntSize,
    onMove: (Long, Float, Float) -> Unit
) {
    val density = LocalDensity.current
    val bw = boxSize.width.toFloat()
    val bh = boxSize.height.toFloat()
    val placement by rememberUpdatedState(item.left to item.top)
    val fontSp = with(density) { (item.fontFrac * bh).toSp() }
    Box(
        Modifier
            .offset { IntOffset((item.left * bw).roundToInt(), (item.top * bh).roundToInt()) }
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
            fontWeight = FontWeight.Medium
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

@Composable
private fun TextEntryDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(18.dp)) {
                Text("Add text", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Text") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.size(8.dp))
                    Button(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text("Add") }
                }
            }
        }
    }
}
