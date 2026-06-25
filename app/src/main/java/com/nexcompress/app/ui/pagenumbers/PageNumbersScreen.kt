package com.nexcompress.app.ui.pagenumbers

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
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.nexcompress.app.domain.model.PageNumberFormat
import com.nexcompress.app.domain.model.StampPosition
import com.nexcompress.app.ui.CompressionViewModel
import com.nexcompress.app.ui.components.SectionLabel
import com.nexcompress.app.ui.watermark.SelectedPdfCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageNumbersScreen(
    viewModel: CompressionViewModel,
    onBack: () -> Unit,
    onStartProcessing: () -> Unit
) {
    val input = viewModel.pageNumberSource
    val position = viewModel.pageNumberPosition

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Page numbers", style = MaterialTheme.typography.titleMedium) },
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
            SelectedPdfCard(name = input?.displayName, size = input?.sizeBytes)

            Spacer(Modifier.height(4.dp))
            SectionLabel("Format")
            val formats = listOf(
                PageNumberFormat.NUMBER_ONLY to "1",
                PageNumberFormat.PAGE_OF_TOTAL to "1 of N",
                PageNumberFormat.PAGE_PREFIX to "Page 1"
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                formats.forEachIndexed { index, (fmt, label) ->
                    SegmentedButton(
                        selected = viewModel.pageNumberFormat == fmt,
                        onClick = { viewModel.updatePageNumberFormat(fmt) },
                        shape = SegmentedButtonDefaults.itemShape(index, formats.size)
                    ) { Text(label) }
                }
            }

            Spacer(Modifier.height(4.dp))
            SectionLabel("Position")
            val onTop = position in TOP_POSITIONS
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = onTop,
                    onClick = { viewModel.updatePageNumberPosition(compose(true, horizontalOf(position))) },
                    shape = SegmentedButtonDefaults.itemShape(0, 2)
                ) { Text("Header") }
                SegmentedButton(
                    selected = !onTop,
                    onClick = { viewModel.updatePageNumberPosition(compose(false, horizontalOf(position))) },
                    shape = SegmentedButtonDefaults.itemShape(1, 2)
                ) { Text("Footer") }
            }
            Spacer(Modifier.height(4.dp))
            val aligns = listOf("Left", "Center", "Right")
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                aligns.forEachIndexed { index, label ->
                    SegmentedButton(
                        selected = horizontalIndexOf(position) == index,
                        onClick = { viewModel.updatePageNumberPosition(compose(onTop, index)) },
                        shape = SegmentedButtonDefaults.itemShape(index, aligns.size)
                    ) { Text(label) }
                }
            }

            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    SectionLabel("Start at")
                    OutlinedTextField(
                        value = viewModel.pageNumberStart.toString(),
                        onValueChange = { raw ->
                            viewModel.updatePageNumberStart(raw.filter { it.isDigit() }.take(5).toIntOrNull() ?: 0)
                        },
                        singleLine = true,
                        enabled = input != null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.size(16.dp))
                Column {
                    SectionLabel("Skip 1st page")
                    Switch(
                        checked = viewModel.pageNumberSkipFirst,
                        onCheckedChange = viewModel::updatePageNumberSkipFirst,
                        enabled = input != null
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            SectionLabel("Save as")
            OutlinedTextField(
                value = viewModel.pageNumberName,
                onValueChange = viewModel::updatePageNumberName,
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
                    viewModel.startPageNumbers()
                    onStartProcessing()
                },
                enabled = input != null,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(Icons.Filled.Bolt, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(8.dp))
                Text("Add page numbers", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

private val TOP_POSITIONS = setOf(
    StampPosition.TOP_LEFT, StampPosition.TOP_CENTER, StampPosition.TOP_RIGHT
)

/** 0 = left, 1 = center, 2 = right. */
private fun horizontalIndexOf(p: StampPosition): Int = when (p) {
    StampPosition.TOP_LEFT, StampPosition.BOTTOM_LEFT -> 0
    StampPosition.TOP_CENTER, StampPosition.BOTTOM_CENTER -> 1
    StampPosition.TOP_RIGHT, StampPosition.BOTTOM_RIGHT -> 2
}

private fun horizontalOf(p: StampPosition): Int = horizontalIndexOf(p)

private fun compose(top: Boolean, horizontalIndex: Int): StampPosition = when (horizontalIndex) {
    0 -> if (top) StampPosition.TOP_LEFT else StampPosition.BOTTOM_LEFT
    2 -> if (top) StampPosition.TOP_RIGHT else StampPosition.BOTTOM_RIGHT
    else -> if (top) StampPosition.TOP_CENTER else StampPosition.BOTTOM_CENTER
}
