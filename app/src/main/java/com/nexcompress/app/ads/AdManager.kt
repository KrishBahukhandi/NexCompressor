package com.nexcompress.app.ads

import android.app.Activity

/**
 * Monetization boundary. The app depends only on this interface so screens stay
 * decoupled from the concrete AdMob SDK (and so ads can be stubbed in tests).
 */
interface AdManager {

    /** Initialize the SDK once, at app startup. Safe to call repeatedly. */
    fun initialize()

    /** Warm up the next interstitial so the Screen 3 -> Screen 4 bridge is instant. */
    fun preloadInterstitial()

    /**
     * MONETIZATION HOOK — Interstitial Ad Bridge.
     *
     * Invoked at the navigation transition between Screen 3 (Processing) and
     * Screen 4 (Results), AFTER the local storage write completes. Shows a capped
     * interstitial if one is ready, then ALWAYS calls [onContinue] to route the
     * user onward — whether the ad shows, fails, or is skipped by the frequency cap.
     */
    fun showInterstitialBridge(activity: Activity, onContinue: () -> Unit)
}
