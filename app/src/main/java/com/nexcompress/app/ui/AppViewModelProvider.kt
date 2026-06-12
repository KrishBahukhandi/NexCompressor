package com.nexcompress.app.ui

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nexcompress.app.NexCompressApplication
import com.nexcompress.app.ui.home.HomeViewModel

/**
 * Single factory that builds every ViewModel from the app's [com.nexcompress.app.di.AppContainer].
 * Keeps construction explicit and DI-framework-free.
 */
object AppViewModelProvider {

    val Factory = viewModelFactory {
        initializer {
            HomeViewModel(nexApp().container.historyRepository)
        }
        initializer {
            val container = nexApp().container
            CompressionViewModel(
                repository = container.historyRepository,
                storage = container.fileStorageManager,
                pdfCompressor = container.pdfCompressor,
                imageConverter = container.imageConverter,
                pdfToImageConverter = container.pdfToImageConverter,
                imagesToPdfConverter = container.imagesToPdfConverter,
                txtToPdfConverter = container.txtToPdfConverter,
                imageEditor = container.imageEditor,
                pdfPageEditor = container.pdfPageEditor,
                pdfMerger = container.pdfMerger,
                pdfSplitter = container.pdfSplitter,
                pdfProtector = container.pdfProtector,
                pdfSigner = container.pdfSigner,
                officeConverter = container.officeConverter,
                onlineConversionService = container.onlineConversionService
            )
        }
    }
}

/** Reaches the Application instance from within a ViewModel factory. */
fun CreationExtras.nexApp(): NexCompressApplication =
    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as NexCompressApplication
