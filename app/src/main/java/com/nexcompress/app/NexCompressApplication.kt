package com.nexcompress.app

import android.app.Application
import com.nexcompress.app.di.AppContainer
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

/** Application entry point. Builds the DI container and warms up the Ads SDK. */
class NexCompressApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Load PDFBox's bundled fonts/resources once (needed by the PDF editing engine).
        PDFBoxResourceLoader.init(applicationContext)
        container = AppContainer(this)
        // Initialize AdMob once; it preloads the first interstitial in the background.
        container.adManager.initialize()
    }
}
