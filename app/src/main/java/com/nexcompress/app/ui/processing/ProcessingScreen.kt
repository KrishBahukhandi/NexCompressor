package com.nexcompress.app.ui.processing

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nexcompress.app.ads.AdManager
import com.nexcompress.app.domain.model.CompressionState
import com.nexcompress.app.ui.CompressionViewModel
import com.nexcompress.app.ui.JobProgress
import com.nexcompress.app.ui.util.findActivity
import kotlinx.coroutines.delay

/** How long an indeterminate job runs before we reassure the user it's not frozen. */
private const val SLOW_FILE_HINT_MS = 8_000L

/**
 * Screen 3 — Processing Status Overlay.
 *
 * Non-cancellable animated loader shown while the background coroutine runs the
 * byte-conversion loop. When the job succeeds (AND the Room write has completed),
 * the Interstitial Ad Bridge fires before routing to Screen 4.
 */
@Composable
fun ProcessingScreen(
    viewModel: CompressionViewModel,
    adManager: AdManager,
    onComplete: () -> Unit,
    onError: () -> Unit,
    onCancel: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val progress by viewModel.progress.collectAsState()
    val activity = LocalContext.current.findActivity()

    // While a task runs, the system back gesture cancels it (same as the button).
    BackHandler(enabled = state is CompressionState.Loading) { onCancel() }

    // Fire the bridge exactly once on success. Saved across config changes /
    // recreation so a rotation while on a finished job can't re-fire the
    // interstitial + re-navigate to Results.
    var bridged by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state, bridged) {
        when (state) {
            is CompressionState.Success -> {
                if (!bridged) {
                    bridged = true
                    // ===== MONETIZATION HOOK — Interstitial Ad Bridge =====
                    // Local storage write already completed inside the ViewModel;
                    // show the interstitial (if capped-allowed), then continue to Results.
                    adManager.showInterstitialBridge(activity) { onComplete() }
                }
            }
            else -> Unit
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when (val current = state) {
                is CompressionState.Error -> {
                    ProcessingError(
                        message = current.message,
                        onDismiss = {
                            viewModel.acknowledgeError()
                            onError()
                        }
                    )
                }
                else -> ProcessingContent(progress = progress, onCancel = onCancel)
            }
        }
    }
}

@Composable
private fun ProcessingContent(progress: JobProgress?, onCancel: () -> Unit) {
    val determinate = progress != null && progress.total > 1
    val messages = remember {
        listOf(
            "Working on your file…",
            "Almost there…",
            "Finishing up…"
        )
    }
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1400)
            index = (index + 1) % messages.size
        }
    }

    // Watchdog: a single large file can keep one native op (PDFBox load/save,
    // page render) busy past where cooperative cancellation can yield. After a
    // few seconds, reassure the user it's working — not frozen — so they don't
    // force-quit mid-write (which would orphan a half-written file).
    var slow by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(SLOW_FILE_HINT_MS)
        slow = true
    }

    // Gentle pulsing halo behind the spinner.
    val transition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Column(
        modifier = Modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "PROCESSING",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(28.dp))
        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                modifier = Modifier.size(86.dp).alpha(pulseAlpha),
                strokeWidth = 3.dp,
                color = MaterialTheme.colorScheme.secondary
            )
            CircularProgressIndicator(
                modifier = Modifier.size(64.dp),
                strokeWidth = 5.dp,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(Modifier.height(28.dp))
        Crossfade(targetState = index, label = "statusLine") { i ->
            Text(
                messages[i],
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
        }

        if (determinate) {
            val p = progress!!
            Spacer(Modifier.height(16.dp))
            Text(
                "${p.done} of ${p.total}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { p.done.toFloat() / p.total.toFloat() },
                modifier = Modifier.fillMaxWidth(0.75f)
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            if (slow) "Large files take a little longer — still working, hang tight."
            else "This runs on your device, so it's quick.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        OutlinedButton(onClick = onCancel) {
            Text("Cancel")
        }
    }
}

@Composable
private fun ProcessingError(message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Processing Failed") },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    )
}
