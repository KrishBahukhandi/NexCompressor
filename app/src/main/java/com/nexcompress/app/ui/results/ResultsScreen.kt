package com.nexcompress.app.ui.results

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Visibility
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nexcompress.app.domain.model.CompressionResult
import com.nexcompress.app.domain.model.CompressionState
import com.nexcompress.app.domain.model.FileType
import com.nexcompress.app.domain.util.FormatUtils
import com.nexcompress.app.ui.CompressionViewModel
import com.nexcompress.app.ui.components.MetricRow
import com.nexcompress.app.ui.components.SectionLabel
import com.nexcompress.app.ui.theme.NexGreen
import com.nexcompress.app.ui.util.IntentUtils

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

    // The results screen is a terminal node — back returns Home cleanly.
    BackHandler { onHome() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("Processing Completed", style = MaterialTheme.typography.titleMedium)
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
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Spacer(Modifier.height(4.dp))
            SuccessBadge(result = result)

            SectionLabel("Transaction Analysis Achieved")
            AnalysisCard(result)

            SectionLabel("Output File Management Controls")
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

            Spacer(Modifier.height(2.dp))
            SavedNote()

            Spacer(Modifier.height(6.dp))
            RatingPrompt(onRate = { IntentUtils.openPlayStoreListing(context) })
        }
    }
}

@Composable
private fun SuccessBadge(result: CompressionResult) {
    // Compression jobs report a savings %; pure conversions report a count instead.
    val headline = when {
        result.savings > 0 -> "Success! Reduced by ${result.efficiencyPercent}%"
        result.items.size > 1 -> "Success! ${result.items.size} files created"
        result.type == FileType.PDF -> "Success! PDF created"
        else -> "Success! Conversion complete"
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
            MetricRow("Original Resource Profile", FormatUtils.formatBytes(result.originalSize))
            MetricRow("Optimized Target Footprint", FormatUtils.formatBytes(result.outputSize))
            HorizontalDivider(
                Modifier.padding(vertical = 6.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
            MetricRow(
                label = "TOTAL FILESYSTEM SAVINGS",
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
                    "Efficiency Delta Scaling",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = NexGreen.copy(alpha = 0.16f)
                ) {
                    Text(
                        "${result.efficiencyPercent}% Down",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = NexGreen,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }
            if (result.isBatch) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "${result.items.size} images converted",
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
