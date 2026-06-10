package com.nexcompress.app.ui.pdfpages

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nexcompress.app.data.processor.PdfPageRenderer
import com.nexcompress.app.domain.model.PdfPageOp
import com.nexcompress.app.ui.CompressionViewModel
import com.nexcompress.app.ui.components.PdfPageThumbnail
import com.nexcompress.app.ui.components.SectionLabel
import com.nexcompress.app.ui.components.rememberPdfRenderer
import sh.calvin.reorderable.ReorderableColumn

/**
 * Lossless PDF page editor — reorder by dragging, rotate or delete individual
 * pages, then export. Selectable text and vectors are preserved.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfPageEditorScreen(
    viewModel: CompressionViewModel,
    onBack: () -> Unit,
    onStartProcessing: () -> Unit
) {
    val input = viewModel.pdfPagesSource
    val ops = viewModel.pdfPageOps
    val rendererState = rememberPdfRenderer(input?.uriString)
    val renderer = rendererState.renderer

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit PDF pages", style = MaterialTheme.typography.titleMedium) },
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
            SectionLabel("Pages (${ops.size})")
            if (rendererState.failed) {
                Text(
                    "Couldn't read this PDF. It may be corrupted or password-protected " +
                        "(unlock it first via Protect PDF).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(8.dp)
                )
            } else if (input == null || ops.isEmpty()) {
                Text(
                    "Reading document…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(8.dp)
                )
            } else {
                Text(
                    "Drag to reorder, rotate, or delete pages. Export keeps text selectable.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ReorderableColumn(
                    list = ops,
                    onSettle = { from, to -> viewModel.movePdfPage(from, to) },
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) { index, op, isDragging ->
                    key(op.key) {
                        PageRow(
                            op = op,
                            renderer = renderer,
                            dragging = isDragging,
                            canDelete = ops.size > 1,
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
                            onRotate = { viewModel.rotatePdfPage(index) },
                            onDelete = { viewModel.deletePdfPage(index) }
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))
                SectionLabel("Output file name")
                OutlinedTextField(
                    value = viewModel.pdfPagesName,
                    onValueChange = { viewModel.updatePdfPagesName(it) },
                    singleLine = true,
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

                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        viewModel.startPdfPageEdit()
                        onStartProcessing()
                    },
                    enabled = ops.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Filled.Bolt, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "EXPORT ${ops.size} ${if (ops.size == 1) "PAGE" else "PAGES"}",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun PageRow(
    op: PdfPageOp,
    renderer: PdfPageRenderer?,
    dragging: Boolean,
    canDelete: Boolean,
    dragHandle: @Composable () -> Unit,
    onRotate: () -> Unit,
    onDelete: () -> Unit
) {
    val elevation by animateDpAsState(if (dragging) 8.dp else 1.dp, label = "dragElevation")
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = elevation)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 2.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            dragHandle()
            Box(
                modifier = Modifier.size(52.dp, 68.dp),
                contentAlignment = Alignment.Center
            ) {
                PdfPageThumbnail(
                    renderer = renderer,
                    pageIndex = op.sourceIndex,
                    rotation = op.rotation,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Page ${op.sourceIndex + 1}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                if (op.rotation % 360 != 0) {
                    Text(
                        "Rotated ${op.rotation % 360}°",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            IconButton(onClick = onRotate, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Filled.Rotate90DegreesCw,
                    contentDescription = "Rotate",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
            IconButton(onClick = onDelete, enabled = canDelete, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Delete page",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
