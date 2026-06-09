package com.nexcompress.app.ui.imageconfig

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
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Lock
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import com.nexcompress.app.domain.model.ImageFormat
import com.nexcompress.app.domain.util.FormatUtils
import com.nexcompress.app.ui.CompressionViewModel
import com.nexcompress.app.ui.components.ImageThumbnail
import com.nexcompress.app.ui.components.SectionLabel
import sh.calvin.reorderable.ReorderableColumn

/**
 * Screen 2 (Image variant) — multi-file batch editor (PRD Flow B).
 * Review picked thumbnails, rename each output, add/remove images (capped at 5),
 * choose the output format + quality, then launch the sequential conversion task.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageConfigScreen(
    viewModel: CompressionViewModel,
    onBack: () -> Unit,
    onStartProcessing: () -> Unit
) {
    val context = LocalContext.current
    val items = viewModel.imageItems
    val format = viewModel.selectedFormat
    val quality = viewModel.imageQuality
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
                title = { Text("Convert Images", style = MaterialTheme.typography.titleMedium) },
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
                SectionLabel("Selected Images (${items.size}/${CompressionViewModel.MAX_IMAGE_SELECTION})")
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
                        "Drag the handle to reorder; files are processed top to bottom.",
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
                        BatchItemRow(
                            item = item,
                            extension = format.extension,
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
                            onRemove = { viewModel.removeImageAt(index) }
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            SectionLabel("Output Format")
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                ImageFormat.entries.forEachIndexed { index, fmt ->
                    SegmentedButton(
                        selected = fmt == format,
                        onClick = { viewModel.setFormat(fmt) },
                        shape = SegmentedButtonDefaults.itemShape(index, ImageFormat.entries.size)
                    ) {
                        Text(fmt.displayName)
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            val lossless = format.lossless
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                SectionLabel("Quality Scale")
                Text(
                    if (lossless) "Lossless" else "$quality%",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Slider(
                value = quality.toFloat(),
                onValueChange = { viewModel.updateImageQuality(it.toInt()) },
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

            Spacer(Modifier.height(6.dp))
            Button(
                onClick = {
                    viewModel.startImageConversion()
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
                    "CONVERT ${items.size} ${if (items.size == 1) "IMAGE" else "IMAGES"}",
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
                    "All conversions run on-device. Your photos never leave this phone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun BatchItemRow(
    item: ImageBatchItem,
    extension: String,
    canRemove: Boolean,
    dragging: Boolean,
    dragHandle: @Composable () -> Unit,
    onRename: (String) -> Unit,
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
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                OutlinedTextField(
                    value = item.outputName,
                    onValueChange = onRename,
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    textStyle = MaterialTheme.typography.bodyMedium,
                    trailingIcon = {
                        Text(
                            ".$extension",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 10.dp)
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "Original: ${FormatUtils.formatBytesCompact(item.source.sizeBytes)}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
