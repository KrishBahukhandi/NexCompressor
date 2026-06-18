package com.nexcompress.app.ads

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback

/**
 * AdMob-backed [AdManager].
 *
 * Every SDK touchpoint is wrapped defensively so a device without Google Play
 * Services (or an ad-load failure) never breaks the core offline workflow —
 * the user simply continues without an ad.
 *
 * NOTE: The IDs below are Google's official TEST units. Swap them (and the
 * APPLICATION_ID in AndroidManifest.xml) for your real ones before release.
 */
class AdMobManager(context: Context) : AdManager {

    private val appContext = context.applicationContext

    private var interstitial: InterstitialAd? = null
    private var isLoading = false
    private var initialized = false

    /** Ad-capping state (PRD §5.2: at most one interstitial every 3 minutes). */
    @Volatile
    private var lastInterstitialAt = 0L

    override fun initialize() {
        if (!AdsConfig.ENABLED) return
        if (initialized) return
        initialized = true
        try {
            MobileAds.initialize(appContext) { /* SDK ready */ }
            preloadInterstitial()
        } catch (t: Throwable) {
            Log.w(TAG, "AdMob initialization failed; ads disabled this session: ${t.message}")
        }
    }

    override fun preloadInterstitial() {
        if (!AdsConfig.ENABLED) return
        if (isLoading || interstitial != null) return
        isLoading = true
        try {
            InterstitialAd.load(
                appContext,
                INTERSTITIAL_UNIT_ID,
                AdRequest.Builder().build(),
                object : InterstitialAdLoadCallback() {
                    override fun onAdLoaded(ad: InterstitialAd) {
                        interstitial = ad
                        isLoading = false
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        interstitial = null
                        isLoading = false
                        Log.d(TAG, "Interstitial failed to load: ${error.message}")
                    }
                }
            )
        } catch (t: Throwable) {
            isLoading = false
            Log.w(TAG, "Interstitial load threw: ${t.message}")
        }
    }

    override fun showInterstitialBridge(activity: Activity, onContinue: () -> Unit) {
        if (!AdsConfig.ENABLED) {
            onContinue()
            return
        }
        val ad = interstitial
        val now = System.currentTimeMillis()
        val capSatisfied = now - lastInterstitialAt >= MIN_INTERVAL_MS

        // No ad ready, or we're inside the frequency cap window -> bridge straight through.
        if (ad == null || !capSatisfied) {
            if (ad == null) preloadInterstitial()
            onContinue()
            return
        }

        // Guard against double-advance from overlapping callbacks.
        var advanced = false
        fun advanceOnce() {
            if (!advanced) {
                advanced = true
                onContinue()
            }
        }

        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                interstitial = null
                preloadInterstitial()
                advanceOnce()
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                interstitial = null
                preloadInterstitial()
                advanceOnce()
            }
        }

        try {
            lastInterstitialAt = now
            ad.show(activity)
        } catch (t: Throwable) {
            Log.w(TAG, "Interstitial show threw: ${t.message}")
            advanceOnce()
        }
    }

    companion object {
        private const val TAG = "AdMobManager"

        /** Google sample interstitial unit — replace before production. */
        private const val INTERSTITIAL_UNIT_ID = "ca-app-pub-3940256099942544/1033173712"

        /** Minimum spacing between interstitials (3 minutes). */
        private const val MIN_INTERVAL_MS = 3 * 60 * 1000L
    }
}
