package com.nexcompress.app.ads

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView

/**
 * MONETIZATION HOOK — Anchor Banner (Screen 1 base).
 *
 * Adaptive-width AdMob banner. Renders a lightweight placeholder inside Compose
 * previews (no SDK) and swallows load failures so an offline device degrades
 * gracefully to empty space rather than crashing.
 */
@Composable
fun BannerAd(
    modifier: Modifier = Modifier,
    adUnitId: String = BANNER_TEST_UNIT_ID
) {
    // Monetization disabled — render nothing.
    if (!AdsConfig.ENABLED) return

    // Compose @Preview / inspection has no real Ads SDK context.
    if (LocalInspectionMode.current) {
        Box(
            modifier = modifier.fillMaxWidth().height(50.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("[ AdMob Banner Anchor ]", style = MaterialTheme.typography.labelSmall)
        }
        return
    }

    val screenWidthDp = LocalConfiguration.current.screenWidthDp

    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { context ->
            AdView(context).apply {
                setAdSize(
                    AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, screenWidthDp)
                )
                this.adUnitId = adUnitId
                runCatching { loadAd(AdRequest.Builder().build()) }
            }
        },
        onRelease = { adView -> runCatching { adView.destroy() } }
    )
}

/** Google sample banner unit — replace before production. */
const val BANNER_TEST_UNIT_ID = "ca-app-pub-3940256099942544/6300978111"
