package com.nexcompress.app.ui.home

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.graphics.Brush
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
import com.nexcompress.app.ui.theme.NexGreen
import com.nexcompress.app.ui.theme.NexIndigo
import com.nexcompress.app.ui.theme.NexViolet
import com.nexcompress.app.domain.model.OnlineConversion
import com.nexcompress.app.ui.util.FileSaver
import com.nexcompress.app.ui.util.IntentUtils
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

/** Bottom-nav tabs on the main screen: the tools dashboard vs. the file history. */
private enum class HomeTab { HOME, HISTORY }

/**
 * Screen 1 — Primary Feature Dashboard.
 * A bottom navigation bar switches between the tools dashboard (action tiles +
 * performance ledger) and the History tab (the scrollable file log backed by
 * Room). The anchored AdMob banner sits above the navigation bar.
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
    onOpenAnnotatePdf: () -> Unit,
    onOpenProcessing: () -> Unit,
    homeViewModel: HomeViewModel = viewModel(factory = AppViewModelProvider.Factory)
) {
    val context = LocalContext.current
    val uiState by homeViewModel.uiState.collectAsStateWithLifecycle()
    var renamingEntry by remember { mutableStateOf<CompressionHistory?>(null) }
    var selectedTab by remember { mutableStateOf(HomeTab.HOME) }
    var pendingOnline by remember { mutableStateOf<OnlineConversion?>(null) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // "Download a copy" of a past output to a user-chosen location (SAF).
    var downloadingEntry by remember { mutableStateOf<CompressionHistory?>(null) }
    val downloadLauncher = rememberLauncherForActivityResult(
        FileSaver.CreateDocumentContract()
    ) { dest ->
        val entry = downloadingEntry
        downloadingEntry = null
        if (dest != null && entry != null) {
            scope.launch {
                val ok = FileSaver.copyToDocument(context, entry.outputUri, dest)
                snackbarHostState.showSnackbar(
                    if (ok) "Saved ${entry.originalName}."
                    else "Couldn't save the file — it may have been deleted."
                )
            }
        }
    }

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
    val annotatePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            compressionViewModel.onAnnotatePicked(uri)
            onOpenAnnotatePdf()
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
                onAnnotatePdf = {
                    scope.launch { drawerState.close() }
                    runCatching { annotatePicker.launch(arrayOf("application/pdf")) }
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
                onConvertDocument = { conversion ->
                    scope.launch { drawerState.close() }
                    pendingOnline = conversion
                    runCatching { onlinePicker.launch(conversion.sourceMimes.toTypedArray()) }
                }
            )
        }
    ) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (selectedTab == HomeTab.HOME) "NexCompress" else "History",
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
            Column {
                // ===== MONETIZATION HOOK — Anchor Banner (base of Screen 1) =====
                Surface(tonalElevation = 3.dp) {
                    BannerAd(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                }
                NavigationBar {
                    NavigationBarItem(
                        selected = selectedTab == HomeTab.HOME,
                        onClick = { selectedTab = HomeTab.HOME },
                        icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                        label = { Text("Home") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == HomeTab.HISTORY,
                        onClick = { selectedTab = HomeTab.HISTORY },
                        icon = { Icon(Icons.Filled.History, contentDescription = null) },
                        label = { Text("History") }
                    )
                }
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
            if (selectedTab == HomeTab.HOME) {
            item {
                Column(Modifier.padding(top = 6.dp, bottom = 8.dp)) {
                    Text(
                        "What do you want to do?",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Everything runs on your device — nothing is uploaded.",
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
                        subtitle = "Shrink file size",
                        onClick = { runCatching { pdfPicker.launch(arrayOf("application/pdf")) } }
                    )
                    FeatureTile(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Filled.Image,
                        accent = NexViolet,
                        title = "Convert images",
                        subtitle = "JPG, PNG, WebP",
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
                        subtitle = "Draw & place",
                        onClick = { runCatching { signPicker.launch(arrayOf("application/pdf")) } }
                    )
                    FeatureTile(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Filled.Layers,
                        accent = NexIndigo,
                        title = "Edit PDF",
                        subtitle = "Reorder, rotate",
                        onClick = { runCatching { pdfPagesPicker.launch(arrayOf("application/pdf")) } }
                    )
                }
            }

            item {
                MoreToolsRow(onClick = { scope.launch { drawerState.open() } })
            }

            item { Spacer(Modifier.height(6.dp)) }
            item { LedgerCard(totalSavings = uiState.totalSavings, totalCount = uiState.totalCount) }
            } else {
            item { Spacer(Modifier.height(2.dp)) }
            if (uiState.history.isEmpty()) {
                item { EmptyHistory() }
            } else {
                items(uiState.history, key = { it.fileId }) { entry ->
                    HistoryRow(
                        entry = entry,
                        onRename = { renamingEntry = entry },
                        onDownload = {
                            downloadingEntry = entry
                            runCatching {
                                downloadLauncher.launch(
                                    FileSaver.CreateDocumentRequest(
                                        suggestedName = entry.originalName,
                                        mimeType = IntentUtils.mimeTypeForName(entry.originalName)
                                    )
                                )
                            }
                        },
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
    // Accent edge: brightest along the top, fading down the sides to a soft outline.
    val accentBorder = Brush.verticalGradient(
        colors = listOf(
            accent,
            accent.copy(alpha = 0.30f),
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )
    )
    Card(
        onClick = onClick,
        modifier = modifier.height(156.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.5.dp, accentBorder)
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

/** A full-width row that opens the tool drawer — makes the other tools discoverable. */
@Composable
private fun MoreToolsRow(onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(12.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.GridView,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "More tools",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "Merge, split, protect, convert & more",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/** Compact at-a-glance card: space saved + files handled, side by side. */
@Composable
private fun LedgerCard(totalSavings: Long, totalCount: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Savings,
                contentDescription = null,
                tint = NexGreen,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Space saved so far",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    FormatUtils.formatBytes(totalSavings),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = NexGreen
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    if (totalCount == 1) "File" else "Files",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "$totalCount",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun HistoryRow(
    entry: CompressionHistory,
    onRename: () -> Unit,
    onDownload: () -> Unit,
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
    val typeLabel = when (entry.type) {
        FileType.PDF -> "PDF"
        FileType.IMAGE -> "Image"
        FileType.DOCUMENT -> "Document"
    }
    val meta = buildString {
        append(typeLabel)
        append(" · ")
        append(FormatUtils.formatBytesCompact(entry.outputSize))
        if (entry.savings > 0) {
            val original = entry.outputSize + entry.savings
            if (original > 0) append(" · saved ${(entry.savings * 100 / original)}%")
        }
    }
    var menuOpen by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(tint.copy(alpha = 0.14f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    entry.originalName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    meta,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (entry.savings > 0) {
                Surface(
                    shape = CircleShape,
                    color = NexGreen.copy(alpha = 0.14f),
                    modifier = Modifier.padding(end = 2.dp)
                ) {
                    Text(
                        "−" + FormatUtils.formatBytesCompact(entry.savings),
                        style = MaterialTheme.typography.labelLarge,
                        color = NexGreen,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = "More actions",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        leadingIcon = { Icon(Icons.Filled.DriveFileRenameOutline, contentDescription = null) },
                        onClick = { menuOpen = false; onRename() }
                    )
                    DropdownMenuItem(
                        text = { Text("Download a copy") },
                        leadingIcon = { Icon(Icons.Outlined.FileDownload, contentDescription = null) },
                        onClick = { menuOpen = false; onDownload() }
                    )
                    DropdownMenuItem(
                        text = { Text("Share") },
                        leadingIcon = { Icon(Icons.Outlined.IosShare, contentDescription = null) },
                        onClick = { menuOpen = false; onShare() }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        leadingIcon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
                        onClick = { menuOpen = false; onDelete() }
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyHistory() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Filled.Inventory2,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(34.dp)
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Nothing here yet",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Compress, convert or sign a file and it'll show up here.",
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
    onAnnotatePdf: () -> Unit,
    onImageStudio: () -> Unit,
    onPdfPages: () -> Unit,
    onMergePdf: () -> Unit,
    onSplitPdf: () -> Unit,
    onProtectPdf: () -> Unit,
    onConvertDocument: (OnlineConversion) -> Unit
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
                label = { Text("Annotate PDF") },
                icon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                badge = {
                    Text(
                        "New",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                },
                selected = false,
                onClick = onAnnotatePdf,
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
            DrawerSectionLabel("Convert documents")
            OnlineConversion.entries.forEach { conversion ->
                NavigationDrawerItem(
                    label = { Text(conversion.title) },
                    icon = { Icon(Icons.Filled.Description, contentDescription = null) },
                    selected = false,
                    onClick = { onConvertDocument(conversion) },
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
