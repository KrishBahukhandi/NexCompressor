package com.nexcompress.app.ui.home

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nexcompress.app.ads.BannerAd
import com.nexcompress.app.data.local.CompressionHistory
import com.nexcompress.app.data.local.savings
import com.nexcompress.app.domain.model.FileType
import com.nexcompress.app.domain.util.FormatUtils
import com.nexcompress.app.ui.AppViewModelProvider
import com.nexcompress.app.ui.CompressionViewModel
import com.nexcompress.app.ui.components.SectionLabel
import com.nexcompress.app.ui.theme.NexGreen
import com.nexcompress.app.ui.theme.NexIndigo
import com.nexcompress.app.ui.theme.NexViolet
import com.nexcompress.app.domain.model.OnlineConversion
import com.nexcompress.app.ui.util.IntentUtils
import com.nexcompress.app.ui.util.NetworkUtils
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

/**
 * Screen 1 — Primary Feature Dashboard.
 * Two action tiles, the cumulative performance ledger, a scrollable history log
 * backed by Room, and the anchored AdMob banner at the base.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    compressionViewModel: CompressionViewModel,
    onOpenPdfConfig: () -> Unit,
    onOpenImageConfig: () -> Unit,
    onOpenPdfToImageConfig: () -> Unit,
    onOpenImagesToPdfConfig: () -> Unit,
    onOpenTxtToPdfConfig: () -> Unit,
    onOpenImageStudio: () -> Unit,
    onOpenPdfPages: () -> Unit,
    onOpenMergePdf: () -> Unit,
    onOpenSplitPdf: () -> Unit,
    onOpenProtectPdf: () -> Unit,
    onOpenSignPdf: () -> Unit,
    onOpenProcessing: () -> Unit,
    homeViewModel: HomeViewModel = viewModel(factory = AppViewModelProvider.Factory)
) {
    val context = LocalContext.current
    val uiState by homeViewModel.uiState.collectAsStateWithLifecycle()
    var renamingEntry by remember { mutableStateOf<CompressionHistory?>(null) }
    var pendingOnline by remember { mutableStateOf<OnlineConversion?>(null) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // SAF picker for PDFs — strictly application/pdf (PRD Flow A).
    val pdfPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            compressionViewModel.onPdfPicked(uri)
            onOpenPdfConfig()
        }
    }

    // SAF picker for the PDF → Images conversion (routes to its own config screen).
    val pdfToImagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            compressionViewModel.onPdfPicked(uri)
            onOpenPdfToImageConfig()
        }
    }

    // SAF multi-picker for the Images → PDF flow.
    val imagesToPdfPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (!uris.isNullOrEmpty()) {
            uris.take(CompressionViewModel.MAX_IMAGE_SELECTION).forEach { uri ->
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            }
            compressionViewModel.onImagesPicked(uris)
            onOpenImagesToPdfConfig()
        }
    }

    // SAF picker for the TXT → PDF flow.
    val txtPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            compressionViewModel.onTxtPicked(uri)
            onOpenTxtToPdfConfig()
        }
    }

    // SAF picker for online Office conversions — source MIME varies per conversion.
    val onlinePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val conversion = pendingOnline
        if (uri != null && conversion != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            compressionViewModel.startOnlineConversion(uri, conversion)
            onOpenProcessing()
        }
        pendingOnline = null
    }

    // SAF multi-picker for images — capped at 5 in the ViewModel (PRD Flow B).
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (!uris.isNullOrEmpty()) {
            uris.take(CompressionViewModel.MAX_IMAGE_SELECTION).forEach { uri ->
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            }
            compressionViewModel.onImagesPicked(uris)
            onOpenImageConfig()
        }
    }

    // --- Advanced tools: SAF pickers ---
    val imageStudioPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            compressionViewModel.onImageEditPicked(uri)
            onOpenImageStudio()
        }
    }
    val pdfPagesPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            compressionViewModel.onPdfPagesPicked(uri)
            onOpenPdfPages()
        }
    }
    val mergePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (!uris.isNullOrEmpty()) {
            uris.forEach { uri ->
                runCatching {
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            compressionViewModel.onMergePicked(uris)
            onOpenMergePdf()
        }
    }
    val splitPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            compressionViewModel.onSplitPicked(uri)
            onOpenSplitPdf()
        }
    }
    val protectPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            compressionViewModel.onProtectPicked(uri)
            onOpenProtectPdf()
        }
    }
    val signPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            compressionViewModel.onSignPicked(uri)
            onOpenSignPdf()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            NexDrawerSheet(
                onCompressPdf = {
                    scope.launch { drawerState.close() }
                    runCatching { pdfPicker.launch(arrayOf("application/pdf")) }
                },
                onConvertImages = {
                    scope.launch { drawerState.close() }
                    runCatching { imagePicker.launch(arrayOf("image/*")) }
                },
                onPdfToImages = {
                    scope.launch { drawerState.close() }
                    runCatching { pdfToImagePicker.launch(arrayOf("application/pdf")) }
                },
                onImagesToPdf = {
                    scope.launch { drawerState.close() }
                    runCatching { imagesToPdfPicker.launch(arrayOf("image/*")) }
                },
                onTxtToPdf = {
                    scope.launch { drawerState.close() }
                    runCatching { txtPicker.launch(arrayOf("text/plain")) }
                },
                onSignPdf = {
                    scope.launch { drawerState.close() }
                    runCatching { signPicker.launch(arrayOf("application/pdf")) }
                },
                onImageStudio = {
                    scope.launch { drawerState.close() }
                    runCatching { imageStudioPicker.launch(arrayOf("image/*")) }
                },
                onPdfPages = {
                    scope.launch { drawerState.close() }
                    runCatching { pdfPagesPicker.launch(arrayOf("application/pdf")) }
                },
                onMergePdf = {
                    scope.launch { drawerState.close() }
                    runCatching { mergePicker.launch(arrayOf("application/pdf")) }
                },
                onSplitPdf = {
                    scope.launch { drawerState.close() }
                    runCatching { splitPicker.launch(arrayOf("application/pdf")) }
                },
                onProtectPdf = {
                    scope.launch { drawerState.close() }
                    runCatching { protectPicker.launch(arrayOf("application/pdf")) }
                },
                onlineConfigured = compressionViewModel.isOnlineConfigured,
                onOnlineConversion = { conversion ->
                    scope.launch { drawerState.close() }
                    if (NetworkUtils.isOnline(context)) {
                        pendingOnline = conversion
                        runCatching { onlinePicker.launch(conversion.sourceMimes.toTypedArray()) }
                    } else {
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                "You're offline. Connect to the internet to use \"${conversion.title}\"."
                            )
                        }
                    }
                }
            )
        }
    ) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "NexCompress",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { scope.launch { drawerState.open() } }) {
                        Icon(Icons.Filled.Menu, contentDescription = "Open menu")
                    }
                },
                actions = { FastChip() },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            // ===== MONETIZATION HOOK — Anchor Banner (base of Screen 1) =====
            Surface(tonalElevation = 3.dp) {
                BannerAd(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = innerPadding.calculateTopPadding() + 4.dp,
                bottom = innerPadding.calculateBottomPadding() + 16.dp
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Column(Modifier.padding(top = 4.dp, bottom = 4.dp)) {
                    SectionLabel("Welcome to Utilities")
                    Text(
                        "Select a fast tool below to begin processing offline.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FeatureTile(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Filled.PictureAsPdf,
                        accent = NexIndigo,
                        title = "Compress PDF",
                        subtitle = "Reduce file size fast",
                        onClick = { runCatching { pdfPicker.launch(arrayOf("application/pdf")) } }
                    )
                    FeatureTile(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Filled.Image,
                        accent = NexViolet,
                        title = "Convert Images",
                        subtitle = "To JPG, PNG or WebP",
                        onClick = { runCatching { imagePicker.launch(arrayOf("image/*")) } }
                    )
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FeatureTile(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Filled.Draw,
                        accent = NexGreen,
                        title = "Sign PDF",
                        subtitle = "Draw & place signature",
                        onClick = { runCatching { signPicker.launch(arrayOf("application/pdf")) } }
                    )
                    FeatureTile(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Filled.Layers,
                        accent = NexIndigo,
                        title = "Edit PDF",
                        subtitle = "Reorder, rotate, delete",
                        onClick = { runCatching { pdfPagesPicker.launch(arrayOf("application/pdf")) } }
                    )
                }
            }

            item { Spacer(Modifier.height(2.dp)) }
            item { SectionLabel("Recent Performance Ledger") }
            item { LedgerCard(totalSavings = uiState.totalSavings, totalCount = uiState.totalCount) }

            item { Spacer(Modifier.height(2.dp)) }
            item { SectionLabel("History Tracking Log") }

            if (uiState.history.isEmpty()) {
                item { EmptyHistory() }
            } else {
                items(uiState.history, key = { it.fileId }) { entry ->
                    HistoryRow(
                        entry = entry,
                        onRename = { renamingEntry = entry },
                        onShare = {
                            IntentUtils.share(
                                context,
                                listOf(entry.outputUri),
                                IntentUtils.mimeTypeForName(entry.originalName)
                            )
                        },
                        onDelete = { homeViewModel.deleteEntry(entry) }
                    )
                }
            }
        }
    }
    } // end ModalNavigationDrawer

    renamingEntry?.let { entry ->
        RenameDialog(
            currentName = entry.originalName,
            onConfirm = { newBase ->
                homeViewModel.renameEntry(entry, newBase)
                renamingEntry = null
            },
            onDismiss = { renamingEntry = null }
        )
    }
}

@Composable
private fun RenameDialog(
    currentName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val extension = currentName.substringAfterLast('.', "")
    var text by remember { mutableStateOf(currentName.substringBeforeLast('.', currentName)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename file") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                trailingIcon = if (extension.isNotEmpty()) {
                    {
                        Text(
                            ".$extension",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 12.dp)
                        )
                    }
                } else null,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text) },
                enabled = text.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun FastChip() {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.16f),
        modifier = Modifier.padding(end = 12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Icon(
                Icons.Filled.Bolt,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.size(4.dp))
            Text(
                "Fast",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.tertiary
            )
        }
    }
}

@Composable
private fun FeatureTile(
    icon: ImageVector,
    accent: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier.height(156.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .background(accent.copy(alpha = 0.16f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(26.dp))
            }
            Column {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun LedgerCard(totalSavings: Long, totalCount: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Savings,
                        contentDescription = null,
                        tint = NexGreen,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "Total Storage Reclaimed",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    FormatUtils.formatBytes(totalSavings),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = NexGreen
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Inventory2,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "Total Files Handled",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    "$totalCount " + if (totalCount == 1) "File" else "Files",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun HistoryRow(
    entry: CompressionHistory,
    onRename: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    val icon = when (entry.type) {
        FileType.PDF -> Icons.Filled.PictureAsPdf
        FileType.IMAGE -> Icons.Filled.Image
        FileType.DOCUMENT -> Icons.Filled.Description
    }
    val tint = when (entry.type) {
        FileType.PDF -> NexIndigo
        FileType.IMAGE -> NexViolet
        FileType.DOCUMENT -> MaterialTheme.colorScheme.tertiary
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    entry.originalName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "-" + FormatUtils.formatBytesCompact(entry.savings),
                    style = MaterialTheme.typography.labelLarge,
                    color = NexGreen
                )
            }
            IconButton(onClick = onRename, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Filled.DriveFileRenameOutline,
                    contentDescription = "Rename",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
            IconButton(onClick = onShare, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Outlined.IosShare,
                    contentDescription = "Share",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun EmptyHistory() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "No conversions yet",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Compress a PDF or convert images to see your savings here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun NexDrawerSheet(
    onCompressPdf: () -> Unit,
    onConvertImages: () -> Unit,
    onPdfToImages: () -> Unit,
    onImagesToPdf: () -> Unit,
    onTxtToPdf: () -> Unit,
    onSignPdf: () -> Unit,
    onImageStudio: () -> Unit,
    onPdfPages: () -> Unit,
    onMergePdf: () -> Unit,
    onSplitPdf: () -> Unit,
    onProtectPdf: () -> Unit,
    onlineConfigured: Boolean,
    onOnlineConversion: (OnlineConversion) -> Unit
) {
    ModalDrawerSheet {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Column(Modifier.padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Bolt,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "NexCompress",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    "Offline file utility & converter",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            HorizontalDivider()

            DrawerSectionLabel("On-device tools")
            NavigationDrawerItem(
                label = { Text("Compress PDF") },
                icon = { Icon(Icons.Filled.PictureAsPdf, contentDescription = null) },
                selected = false,
                onClick = onCompressPdf,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
            NavigationDrawerItem(
                label = { Text("Convert Images") },
                icon = { Icon(Icons.Filled.Image, contentDescription = null) },
                selected = false,
                onClick = onConvertImages,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
            NavigationDrawerItem(
                label = { Text("PDF → Images") },
                icon = { Icon(Icons.Filled.Collections, contentDescription = null) },
                selected = false,
                onClick = onPdfToImages,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
            NavigationDrawerItem(
                label = { Text("Images → PDF") },
                icon = { Icon(Icons.Filled.PictureAsPdf, contentDescription = null) },
                selected = false,
                onClick = onImagesToPdf,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
            NavigationDrawerItem(
                label = { Text("Text → PDF") },
                icon = { Icon(Icons.AutoMirrored.Filled.TextSnippet, contentDescription = null) },
                selected = false,
                onClick = onTxtToPdf,
                modifier = Modifier.padding(horizontal = 12.dp)
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            DrawerSectionLabel("Edit & sign · on-device")
            NavigationDrawerItem(
                label = { Text("Sign PDF") },
                icon = { Icon(Icons.Filled.Draw, contentDescription = null) },
                selected = false,
                onClick = onSignPdf,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
            NavigationDrawerItem(
                label = { Text("Edit PDF pages") },
                icon = { Icon(Icons.Filled.Layers, contentDescription = null) },
                selected = false,
                onClick = onPdfPages,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
            NavigationDrawerItem(
                label = { Text("Merge PDFs") },
                icon = { Icon(Icons.Filled.MergeType, contentDescription = null) },
                selected = false,
                onClick = onMergePdf,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
            NavigationDrawerItem(
                label = { Text("Split PDF") },
                icon = { Icon(Icons.Filled.ContentCut, contentDescription = null) },
                selected = false,
                onClick = onSplitPdf,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
            NavigationDrawerItem(
                label = { Text("Protect PDF") },
                icon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                selected = false,
                onClick = onProtectPdf,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
            NavigationDrawerItem(
                label = { Text("Image Studio") },
                icon = { Icon(Icons.Filled.Crop, contentDescription = null) },
                selected = false,
                onClick = onImageStudio,
                modifier = Modifier.padding(horizontal = 12.dp)
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            DrawerSectionLabel(
                if (onlineConfigured) "More conversions · Online" else "More conversions · Demo"
            )
            OnlineConversion.entries.forEach { conversion ->
                NavigationDrawerItem(
                    label = { Text(conversion.title) },
                    icon = { Icon(Icons.Filled.Description, contentDescription = null) },
                    badge = {
                        Text(
                            if (onlineConfigured) "Online" else "Demo",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    selected = false,
                    onClick = { onOnlineConversion(conversion) },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun DrawerSectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 28.dp, top = 14.dp, bottom = 4.dp)
    )
}
