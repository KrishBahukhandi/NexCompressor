package com.nexcompress.app.ui.watermark

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nexcompress.app.domain.util.FormatUtils
import com.nexcompress.app.ui.CompressionViewModel
import com.nexcompress.app.ui.components.SectionLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatermarkScreen(
    viewModel: CompressionViewModel,
    onBack: () -> Unit,
    onStartProcessing: () -> Unit
) {
    val input = viewModel.watermarkSource

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Watermark PDF", style = MaterialTheme.typography.titleMedium) },
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
            SectionLabel("Selected file")
            SelectedPdfCard(
                name = input?.displayName,
                size = input?.sizeBytes
            )

            Spacer(Modifier.height(4.dp))
            SectionLabel("Watermark text")
            OutlinedTextField(
                value = viewModel.watermarkText,
                onValueChange = viewModel::updateWatermarkText,
                singleLine = true,
                enabled = input != null,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                SectionLabel("Opacity")
                Text(
                    "${(viewModel.watermarkOpacity * 100).toInt()}%",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Slider(
                value = viewModel.watermarkOpacity,
                onValueChange = viewModel::updateWatermarkOpacity,
                valueRange = 0.05f..1f,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(4.dp))
            SectionLabel("Style")
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = viewModel.watermarkDiagonal,
                    onClick = { viewModel.updateWatermarkDiagonal(true) },
                    shape = SegmentedButtonDefaults.itemShape(0, 2)
                ) { Text("Diagonal") }
                SegmentedButton(
                    selected = !viewModel.watermarkDiagonal,
                    onClick = { viewModel.updateWatermarkDiagonal(false) },
                    shape = SegmentedButtonDefaults.itemShape(1, 2)
                ) { Text("Horizontal") }
            }

            Spacer(Modifier.height(4.dp))
            SectionLabel("Layout")
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = !viewModel.watermarkTiled,
                    onClick = { viewModel.updateWatermarkTiled(false) },
                    shape = SegmentedButtonDefaults.itemShape(0, 2)
                ) { Text("Single") }
                SegmentedButton(
                    selected = viewModel.watermarkTiled,
                    onClick = { viewModel.updateWatermarkTiled(true) },
                    shape = SegmentedButtonDefaults.itemShape(1, 2)
                ) { Text("Tiled") }
            }

            Spacer(Modifier.height(4.dp))
            SectionLabel("Color")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                WATERMARK_COLORS.forEach { (name, argb) ->
                    ColorSwatch(
                        name = name,
                        argb = argb,
                        selected = viewModel.watermarkColor == argb,
                        onClick = { viewModel.updateWatermarkColor(argb) }
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            SectionLabel("Save as")
            OutlinedTextField(
                value = viewModel.watermarkName,
                onValueChange = viewModel::updateWatermarkName,
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

            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    viewModel.startWatermark()
                    onStartProcessing()
                },
                enabled = input != null && viewModel.watermarkText.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(Icons.Filled.Bolt, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(8.dp))
                Text("Add watermark", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            }
            Text(
                "The watermark is stamped on every page. Your text and the rest of the page stay intact.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Preset watermark colors (label -> ARGB) — labels are read out by TalkBack. */
private val WATERMARK_COLORS = listOf(
    "Grey" to 0xFF9E9E9E.toInt(),
    "Red" to 0xFFEF4444.toInt(),
    "Blue" to 0xFF2563EB.toInt(),
    "Green" to 0xFF16A34A.toInt(),
    "Amber" to 0xFFF59E0B.toInt(),
    "Black" to 0xFF111111.toInt()
)

@Composable
private fun ColorSwatch(name: String, argb: Int, selected: Boolean, onClick: () -> Unit) {
    val ring = if (selected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.outlineVariant
    // Outer box is a full 48dp accessible touch target; the visible swatch is
    // centered inside it. Labeled + RadioButton role for TalkBack.
    Box(
        modifier = Modifier
            .size(48.dp)
            .clickable(onClick = onClick)
            .semantics {
                role = Role.RadioButton
                contentDescription = if (selected) "$name, selected" else name
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(Color(argb))
                .border(if (selected) 3.dp else 1.dp, ring, CircleShape)
        )
    }
}

@Composable
internal fun SelectedPdfCard(name: String?, size: Long?) {
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
                Icons.Filled.PictureAsPdf,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(34.dp)
            )
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = name ?: "Reading document…",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Size: " + (size?.let { FormatUtils.formatBytes(it) } ?: "—"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
