package com.nexcompress.app.ui.imagestopdf

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PictureAsPdf
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nexcompress.app.domain.model.ImageBatchItem
import com.nexcompress.app.domain.util.FormatUtils
import com.nexcompress.app.ui.CompressionViewModel
import com.nexcompress.app.ui.components.ImageThumbnail
import com.nexcompress.app.ui.components.SectionLabel
import sh.calvin.reorderable.ReorderableColumn

/**
 * Config screen for Images → PDF. Reorder the pages (drag handle), add/remove
 * images, name the output PDF and pick an embedded-image quality, then combine
 * everything into a single multi-page document.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImagesToPdfConfigScreen(
    viewModel: CompressionViewModel,
    onBack: () -> Unit,
    onStartProcessing: () -> Unit
) {
    val context = LocalContext.current
    val items = viewModel.imageItems
    val name = viewModel.imagesToPdfName
    val quality = viewModel.imagesToPdfQuality
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
                title = { Text("Images → PDF", style = MaterialTheme.typography.titleMedium) },
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
                SectionLabel("Pages (${items.size}/${CompressionViewModel.MAX_IMAGE_SELECTION})")
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
                        "Drag the handle to set page order.",
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
                        PageRow(
                            item = item,
                            pageNumber = index + 1,
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
                            onRemove = { viewModel.removeImageAt(index) }
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            SectionLabel("Output PDF Name")
            OutlinedTextField(
                value = name,
                onValueChange = viewModel::updateImagesToPdfName,
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                trailingIcon = {
                    Text(
                        ".pdf",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 12.dp)
                    )
                },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                SectionLabel("Image Quality")
                Text(
                    "$quality%",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Slider(
                value = quality.toFloat(),
                onValueChange = { viewModel.updateImagesToPdfQuality(it.toInt()) },
                valueRange = 10f..100f,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(6.dp))
            Button(
                onClick = {
                    viewModel.startImagesToPdf()
                    onStartProcessing()
                },
                enabled = items.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(Icons.Filled.PictureAsPdf, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(8.dp))
                Text(
                    "CREATE PDF",
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
                    "The PDF is built on-device. Your photos never leave this phone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun PageRow(
    item: ImageBatchItem,
    pageNumber: Int,
    canRemove: Boolean,
    dragging: Boolean,
    dragHandle: @Composable () -> Unit,
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
            ImageThumbnail(
                uriString = item.source.uriString,
                modifier = Modifier.size(56.dp)
            )
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Page $pageNumber",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "Original: ${FormatUtils.formatBytesCompact(item.source.sizeBytes)}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onRemove, enabled = canRemove) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Remove page",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
