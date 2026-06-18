package com.nexcompress.app.ui.about

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
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nexcompress.app.BuildConfig
import com.nexcompress.app.ui.components.SectionLabel
import com.nexcompress.app.ui.theme.NexGreen
import com.nexcompress.app.ui.util.IntentUtils

/**
 * Hosted privacy policy URL (required by the Play Store when shipping ads).
 * Leave blank until one is published — the "Read the full policy" button stays
 * hidden while it's empty, so nothing links to a dead page.
 */
private const val PRIVACY_POLICY_URL = ""

private data class Faq(val q: String, val a: String)

private val FAQ = listOf(
    Faq(
        "Where do my files go?",
        "Outputs are saved to Downloads/NexCompress. Your originals are never " +
            "changed or deleted — every result is a new file."
    ),
    Faq(
        "Does anything upload to the internet?",
        "No. Every tool — compress, convert, edit, sign, OCR — runs on this device. " +
            "The only network use is loading ads."
    ),
    Faq(
        "How do I get a file into the app?",
        "Open a tool from the home screen, or share a photo / PDF to NexCompress " +
            "from Gallery, Files or any app's share menu."
    ),
    Faq(
        "Which compression level should I pick?",
        "Recommended suits most PDFs. Smallest file squeezes images hardest; " +
            "Best quality barely touches them — good for photo-heavy documents."
    )
)

/** About / Privacy / Help — a single offline-first info screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About & privacy", style = MaterialTheme.typography.titleMedium) },
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
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Bolt, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.size(8.dp))
                Column {
                    Text("NexCompress", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "Version ${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ---- Privacy statement ----
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = NexGreen.copy(alpha = 0.10f)
                )
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.CloudOff, contentDescription = null, tint = NexGreen, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("Your files stay on your device", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Every operation — compressing, converting, editing, signing and OCR — " +
                            "is performed entirely on this phone. Your documents and photos are " +
                            "never uploaded to any server. NexCompress also never modifies or " +
                            "deletes your originals: each result is saved as a new file in " +
                            "Downloads/NexCompress.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(10.dp))
                    PermissionLine(
                        "Storage",
                        "No storage permission is requested — files are accessed only when you " +
                            "pick or share them."
                    )
                    PermissionLine(
                        "Internet",
                        "Used solely by the ad service to load ads. No file data is sent over it."
                    )
                    if (PRIVACY_POLICY_URL.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = { IntentUtils.openUrl(context, PRIVACY_POLICY_URL) },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(8.dp))
                            Text("Read the full privacy policy")
                        }
                    }
                }
            }

            // ---- FAQ / help ----
            Spacer(Modifier.height(2.dp))
            SectionLabel("Help & FAQ")
            FAQ.forEach { item ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(item.q, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            item.a,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PermissionLine(name: String, detail: String) {
    Row(modifier = Modifier.padding(top = 6.dp)) {
        Icon(
            Icons.Filled.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp).padding(top = 2.dp)
        )
        Spacer(Modifier.size(8.dp))
        Column {
            Text(name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
