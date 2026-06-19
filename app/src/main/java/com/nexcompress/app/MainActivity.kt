package com.nexcompress.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.nexcompress.app.ui.navigation.NexCompressNavGraph
import com.nexcompress.app.ui.share.SharedInput
import com.nexcompress.app.ui.share.parseShareIntent
import com.nexcompress.app.ui.theme.NexCompressTheme

/** Single-Activity host for the Compose navigation graph. */
class MainActivity : ComponentActivity() {

    // Holds a file shared into the app (ACTION_SEND); read by the nav graph,
    // which routes it to the right tool, then clears it.
    private var pendingShare by mutableStateOf<SharedInput?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val adManager = (application as NexCompressApplication).container.adManager
        pendingShare = parseShareIntent(intent, contentResolver)

        setContent {
            NexCompressTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    NexCompressNavGraph(
                        navController = navController,
                        adManager = adManager,
                        sharedInput = pendingShare,
                        onSharedInputHandled = { pendingShare = null }
                    )
                }
            }
        }
    }

    // Handles a share that arrives while the app is already running.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        parseShareIntent(intent, contentResolver)?.let { pendingShare = it }
    }
}
