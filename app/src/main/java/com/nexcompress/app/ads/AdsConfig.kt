package com.nexcompress.app.ads

/**
 * Master on/off switch for monetization. When false the app ships with NO ads —
 * the banner renders nothing, the AdMob SDK is never initialized, and the
 * interstitial bridge passes straight through. Flip to true to re-enable AdMob.
 */
object AdsConfig {
    const val ENABLED = false
}
