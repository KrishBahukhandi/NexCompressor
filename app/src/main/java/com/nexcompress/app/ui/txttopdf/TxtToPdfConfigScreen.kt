package com.nexcompress.app.ui.txttopdf

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
import androidx.compose.material.icons.automirrored.filled.TextSnippet
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nexcompress.app.domain.util.FormatUtils
import com.nexcompress.app.ui.CompressionViewModel
import com.nexcompress.app.ui.components.SectionLabel

private val TEXT_SIZES = listOf("Small" to 9, "Medium" to 11, "Large" to 14)

/** Config screen for TXT → PDF: name the output, pick a text size, and convert. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TxtToPdfConfigScreen(
    viewModel: CompressionViewModel,
    onBack: () -> Unit,
    onStartProcessing: () -> Unit
) {
    val input = viewModel.txtInput
    val name = viewModel.txtPdfName
    val fontSize = viewModel.txtFontSize

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Text → PDF", style = MaterialTheme.typography.titleMedium) },
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
            SectionLabel("Selected File Metrics")
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.TextSnippet,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(34.dp)
                    )
                    Spacer(Modifier.size(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = input?.displayName ?: "Reading file…",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "Detected Original Size: " +
                                (input?.let { FormatUtils.formatBytes(it.sizeBytes) } ?: "—"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            SectionLabel("Output PDF Name")
            OutlinedTextField(
                value = name,
                onValueChange = viewModel::updateTxtPdfName,
                singleLine = true,
                enabled = input != null,
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

            Spacer(Modifier.height(4.dp))
            SectionLabel("Text Size")
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                TEXT_SIZES.forEachIndexed { index, (label, value) ->
                    SegmentedButton(
                        selected = value == fontSize,
                        onClick = { viewModel.updateTxtFontSize(value) },
                        shape = SegmentedButtonDefaults.itemShape(index, TEXT_SIZES.size)
                    ) {
                        Text(label)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    viewModel.startTxtToPdf()
                    onStartProcessing()
                },
                enabled = input != null,
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
            Text(
                "Your text is laid out into a clean, paginated A4 PDF saved to Downloads/NexCompress.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp).padding(top = 2.dp)
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    "Processing is performed locally on this device. Your file never " +
                        "uploads to any cloud servers.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
