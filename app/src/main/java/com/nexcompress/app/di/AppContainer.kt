package com.nexcompress.app.di

import android.content.Context
import com.nexcompress.app.ads.AdManager
import com.nexcompress.app.ads.AdMobManager
import com.nexcompress.app.data.local.NexCompressDatabase
import com.nexcompress.app.data.processor.FileStorageManager
import com.nexcompress.app.data.processor.ImageConverter
import com.nexcompress.app.data.processor.ImagesToPdfConverter
import com.nexcompress.app.data.processor.PdfCompressor
import com.nexcompress.app.data.processor.PdfToImageConverter
import com.nexcompress.app.data.processor.TxtToPdfConverter
import com.nexcompress.app.data.remote.OnlineConversionService
import com.nexcompress.app.data.remote.RestConversionService
import com.nexcompress.app.data.repository.HistoryRepository

/**
 * Lightweight manual dependency container (no annotation-processing DI needed).
 * Owns app-scoped singletons and is created once in [NexCompressApplication].
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    private val database by lazy { NexCompressDatabase.getInstance(appContext) }

    val fileStorageManager: FileStorageManager by lazy { FileStorageManager(appContext) }
    val historyRepository: HistoryRepository by lazy {
        HistoryRepository(database.historyDao(), fileStorageManager)
    }
    val pdfCompressor: PdfCompressor by lazy { PdfCompressor(appContext, fileStorageManager) }
    val imageConverter: ImageConverter by lazy { ImageConverter(appContext, fileStorageManager) }
    val pdfToImageConverter: PdfToImageConverter by lazy {
        PdfToImageConverter(appContext, fileStorageManager)
    }
    val imagesToPdfConverter: ImagesToPdfConverter by lazy {
        ImagesToPdfConverter(appContext, fileStorageManager)
    }
    val txtToPdfConverter: TxtToPdfConverter by lazy {
        TxtToPdfConverter(appContext, fileStorageManager)
    }
    val onlineConversionService: OnlineConversionService by lazy {
        RestConversionService(appContext, fileStorageManager)
    }
    val adManager: AdManager by lazy { AdMobManager(appContext) }
}
