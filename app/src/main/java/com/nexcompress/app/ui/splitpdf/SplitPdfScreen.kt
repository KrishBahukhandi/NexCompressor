package com.nexcompress.app.ui.splitpdf

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nexcompress.app.ui.CompressionViewModel
import com.nexcompress.app.ui.components.PdfPageThumbnail
import com.nexcompress.app.ui.components.SectionLabel
import com.nexcompress.app.ui.components.rememberPdfRenderer

/**
 * Split a PDF — either extract a chosen set of pages into one new PDF, or burst
 * every page into its own single-page file. Output pages stay selectable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SplitPdfScreen(
    viewModel: CompressionViewModel,
    onBack: () -> Unit,
    onStartProcessing: () -> Unit
) {
    val input = viewModel.splitSource
    val pageCount = viewModel.splitPageCount
    val rendererState = rememberPdfRenderer(input?.uriString)
    val renderer = rendererState.renderer
    var splitAll by rememberSaveable { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<Int>() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Split PDF", style = MaterialTheme.typography.titleMedium) },
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
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(4.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = !splitAll,
                    onClick = { splitAll = false },
                    shape = SegmentedButtonDefaults.itemShape(0, 2)
                ) { Text("Extract pages") }
                SegmentedButton(
                    selected = splitAll,
                    onClick = { splitAll = true },
                    shape = SegmentedButtonDefaults.itemShape(1, 2)
                ) { Text("Split all") }
            }

            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = viewModel.splitName,
                onValueChange = { viewModel.updateSplitName(it) },
                singleLine = true,
                label = { Text("Output file name") },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(10.dp))

            if (rendererState.failed) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Couldn't read this PDF. It may be corrupted or password-protected " +
                            "(unlock it first via Protect PDF).",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(24.dp)
                    )
                }
                return@Column
            }
            if (input == null || pageCount == 0) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (input != null) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                    } else {
                        Text("Reading document…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                return@Column
            }

            if (splitAll) {
                // weight(1f), NOT fillMaxSize — the latter consumes all remaining
                // height and pushes the action button off-screen.
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.ContentCut,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Each of the $pageCount pages becomes its own PDF.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            } else {
                SectionLabel("Tap to select pages (${selected.size} chosen)")
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 96.dp),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(pageCount) { index ->
                        val isSelected = selected.contains(index)
                        PageTile(
                            renderer = renderer,
                            pageIndex = index,
                            selected = isSelected,
                            onToggle = {
                                if (isSelected) selected.remove(index) else selected.add(index)
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            val canSubmit = if (splitAll) pageCount > 0 else selected.isNotEmpty()
            Button(
                onClick = {
                    if (splitAll) viewModel.startSplitEach()
                    else viewModel.startSplitExtract(selected.toList())
                    onStartProcessing()
                },
                enabled = canSubmit,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(Icons.Filled.Bolt, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(8.dp))
                Text(
                    if (splitAll) "SPLIT INTO $pageCount FILES"
                    else "EXTRACT ${selected.size} ${if (selected.size == 1) "PAGE" else "PAGES"}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun PageTile(
    renderer: com.nexcompress.app.data.processor.PdfPageRenderer?,
    pageIndex: Int,
    selected: Boolean,
    onToggle: () -> Unit
) {
    Card(
        onClick = onToggle,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.fillMaxWidth().aspectRatio(0.72f)
    ) {
        Box(Modifier.fillMaxSize()) {
            PdfPageThumbnail(
                renderer = renderer,
                pageIndex = pageIndex,
                modifier = Modifier.fillMaxSize().padding(4.dp)
            )
            Surface(
                color = MaterialTheme.colorScheme.background.copy(alpha = 0.85f),
                shape = RoundedCornerShape(topStart = 8.dp, bottomEnd = 0.dp),
                modifier = Modifier.align(Alignment.BottomStart)
            ) {
                Text(
                    "${pageIndex + 1}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            if (selected) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(20.dp)
                )
            }
        }
    }
}
