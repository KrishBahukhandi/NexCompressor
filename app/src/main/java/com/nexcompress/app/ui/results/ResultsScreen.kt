package com.nexcompress.app.ui.results

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nexcompress.app.domain.model.CompressionResult
import com.nexcompress.app.domain.model.CompressionState
import com.nexcompress.app.domain.model.FileType
import com.nexcompress.app.domain.model.OutputItem
import com.nexcompress.app.domain.util.FormatUtils
import com.nexcompress.app.ui.CompressionViewModel
import com.nexcompress.app.ui.components.MetricRow
import com.nexcompress.app.ui.components.SectionLabel
import com.nexcompress.app.ui.theme.NexGreen
import com.nexcompress.app.ui.util.FileSaver
import com.nexcompress.app.ui.util.IntentUtils
import kotlinx.coroutines.launch

/**
 * Screen 4 — Processing Results & Analytics.
 * Renders the before/after footprint, total savings, the efficiency delta badge,
 * and the external Preview / Share controls.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultsScreen(
    viewModel: CompressionViewModel,
    onHome: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val result = (state as? CompressionState.Success)?.result
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // --- "Download a copy" (SAF): outputs already live in Downloads/NexCompress;
    // this is an extra "Save as" to any location the user picks. ---
    var pendingDownload by remember { mutableStateOf<OutputItem?>(null) }
    val downloadOneLauncher = rememberLauncherForActivityResult(
        FileSaver.CreateDocumentContract()
    ) { dest ->
        val item = pendingDownload
        pendingDownload = null
        if (dest != null && item != null) {
            scope.launch {
                val ok = FileSaver.copyToDocument(context, item.uri, dest)
                snackbarHostState.showSnackbar(
                    if (ok) "Saved ${item.displayName}." else "Couldn't save the file."
                )
            }
        }
    }
    val downloadAllLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { tree ->
        val items = result?.items.orEmpty()
        if (tree != null && items.isNotEmpty()) {
            scope.launch {
                val saved = FileSaver.copyAllToTree(context, items, tree)
                snackbarHostState.showSnackbar(
                    when {
                        saved == items.size -> "Saved $saved files."
                        saved > 0 -> "Saved $saved of ${items.size} files."
                        else -> "Couldn't save the files."
                    }
                )
            }
        }
    }

    // The results screen is a terminal node — back returns Home cleanly.
    BackHandler { onHome() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text("Done", style = MaterialTheme.typography.titleMedium)
                },
                navigationIcon = {
                    IconButton(onClick = onHome) {
                        Icon(Icons.Filled.Home, contentDescription = "Home")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        if (result == null) {
            EmptyResult(modifier = Modifier.fillMaxSize().padding(innerPadding), onHome = onHome)
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Spacer(Modifier.height(4.dp))
            SuccessBadge(result = result)

            SectionLabel("Summary")
            AnalysisCard(result)

            SectionLabel("What's next")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = {
                        result.primaryUri?.let {
                            IntentUtils.view(
                                context, it,
                                IntentUtils.mimeTypeForName(result.items.firstOrNull()?.displayName.orEmpty())
                            )
                        }
                    },
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Filled.Visibility, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(if (result.type == FileType.PDF) "Preview" else "View")
                }
                Button(
                    onClick = {
                        IntentUtils.share(
                            context,
                            result.items.map { it.uri },
                            IntentUtils.mimeTypeForName(result.items.firstOrNull()?.displayName.orEmpty())
                        )
                    },
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Outlined.IosShare, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Share")
                }
            }

            // Save an extra copy anywhere the user chooses.
            OutlinedButton(
                onClick = {
                    if (result.isBatch) {
                        runCatching { downloadAllLauncher.launch(null) }
                    } else {
                        result.items.firstOrNull()?.let { item ->
                            pendingDownload = item
                            runCatching {
                                downloadOneLauncher.launch(
                                    FileSaver.CreateDocumentRequest(
                                        suggestedName = item.displayName,
                                        mimeType = IntentUtils.mimeTypeForName(item.displayName)
                                    )
                                )
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Outlined.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(if (result.isBatch) "Save a copy of all (${result.items.size})" else "Save a copy")
            }

            Spacer(Modifier.height(2.dp))
            SavedNote()

            Spacer(Modifier.height(6.dp))
            RatingPrompt(onRate = { IntentUtils.openPlayStoreListing(context) })
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SuccessBadge(result: CompressionResult) {
    // Compression jobs report a savings %; pure conversions report a count instead.
    val headline = when {
        result.savings > 0 -> "Reduced by ${result.efficiencyPercent}%"
        result.items.size > 1 -> "${result.items.size} files ready"
        result.type == FileType.PDF -> "Your PDF is ready"
        else -> "All done"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = NexGreen,
            modifier = Modifier.size(28.dp)
        )
        Spacer(Modifier.size(10.dp))
        Text(
            headline,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun AnalysisCard(result: CompressionResult) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.padding(18.dp)) {
            if (result.savings > 0) {
                // Compression framing: before / after / bytes reclaimed.
                MetricRow("Original size", FormatUtils.formatBytes(result.originalSize))
                MetricRow("New size", FormatUtils.formatBytes(result.outputSize))
                HorizontalDivider(
                    Modifier.padding(vertical = 6.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
                MetricRow(
                    label = "You saved",
                    value = FormatUtils.formatBytes(result.savings),
                    emphasize = true,
                    valueColor = NexGreen
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Reduction",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = NexGreen.copy(alpha = 0.16f)
                    ) {
                        Text(
                            "${result.efficiencyPercent}% smaller",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = NexGreen,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }
            } else {
                // Conversion / edit framing: a new asset was produced, nothing was
                // "saved" — a 0%-down badge here would read like a failure.
                if (result.originalSize > 0) {
                    MetricRow("Input size", FormatUtils.formatBytes(result.originalSize))
                }
                HorizontalDivider(
                    Modifier.padding(vertical = 6.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
                MetricRow(
                    label = if (result.items.size > 1) "Total size" else "File size",
                    value = FormatUtils.formatBytes(result.outputSize),
                    emphasize = true
                )
            }
            if (result.isBatch) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "${result.items.size} files created",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SavedNote() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = NexGreen,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.size(8.dp))
        Text(
            "Saved to your Downloads/NexCompress folder.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun RatingPrompt(onRate: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(
            "Rate NexCompress if you enjoy using this tool!",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        Row {
            repeat(5) {
                IconButton(onClick = onRate, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = "Rate",
                        tint = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyResult(modifier: Modifier = Modifier, onHome: () -> Unit) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("No results to display", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onHome) { Text("Back to Home") }
    }
}
