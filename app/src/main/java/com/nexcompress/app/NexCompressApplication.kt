package com.nexcompress.app

import android.app.Application
import com.nexcompress.app.di.AppContainer

/** Application entry point. Builds the DI container and warms up the Ads SDK. */
class NexCompressApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Initialize AdMob once; it preloads the first interstitial in the background.
        container.adManager.initialize()
    }
}
