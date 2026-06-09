package com.nexcompress.app.ui

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexcompress.app.data.processor.FileStorageManager
import com.nexcompress.app.data.processor.ImageConverter
import com.nexcompress.app.data.processor.ImagesToPdfConverter
import com.nexcompress.app.data.processor.PdfCompressor
import com.nexcompress.app.data.processor.PdfToImageConverter
import com.nexcompress.app.data.processor.TxtToPdfConverter
import com.nexcompress.app.data.remote.OnlineConversionService
import com.nexcompress.app.data.repository.HistoryRepository
import com.nexcompress.app.domain.model.CompressionException
import com.nexcompress.app.domain.model.CompressionProfile
import com.nexcompress.app.domain.model.CompressionResult
import com.nexcompress.app.domain.model.CompressionState
import com.nexcompress.app.domain.model.FileType
import com.nexcompress.app.domain.model.ImageBatchItem
import com.nexcompress.app.domain.model.ImageFormat
import com.nexcompress.app.domain.model.OnlineConversion
import com.nexcompress.app.domain.model.PickedFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shared ViewModel for the compression flow (Config -> Processing -> Results).
 * Scoped to the host Activity so all three screens observe the same job state.
 */
class CompressionViewModel(
    private val repository: HistoryRepository,
    private val storage: FileStorageManager,
    private val pdfCompressor: PdfCompressor,
    private val imageConverter: ImageConverter,
    private val pdfToImageConverter: PdfToImageConverter,
    private val imagesToPdfConverter: ImagesToPdfConverter,
    private val txtToPdfConverter: TxtToPdfConverter,
    private val onlineConversionService: OnlineConversionService
) : ViewModel() {

    /** True when a real online endpoint + key are configured (else demo mode). */
    val isOnlineConfigured: Boolean get() = onlineConversionService.isConfigured

    // --- PDF selection / config (Screen 2) ---
    var pdfInput by mutableStateOf<PickedFile?>(null)
        private set
    /** Editable output base name for the compressed PDF (no extension). */
    var pdfOutputName by mutableStateOf("")
        private set
    var selectedProfile by mutableStateOf(CompressionProfile.DEFAULT)
        private set

    // --- PDF → Images config ---
    var pdfImageFormat by mutableStateOf(ImageFormat.JPEG)
        private set
    var pdfImageQuality by mutableStateOf(DEFAULT_IMAGE_QUALITY)
        private set

    // --- Image selection / config (Flow B) ---
    /** The batch of picked images, each with its own editable output name. */
    var imageItems by mutableStateOf<List<ImageBatchItem>>(emptyList())
        private set
    var selectedFormat by mutableStateOf(ImageFormat.DEFAULT)
        private set
    var imageQuality by mutableStateOf(DEFAULT_IMAGE_QUALITY)
        private set

    // --- Images → PDF config ---
    var imagesToPdfName by mutableStateOf("")
        private set
    var imagesToPdfQuality by mutableStateOf(DEFAULT_IMAGE_QUALITY)
        private set

    // --- TXT → PDF config ---
    var txtInput by mutableStateOf<PickedFile?>(null)
        private set
    var txtPdfName by mutableStateOf("")
        private set
    var txtFontSize by mutableStateOf(DEFAULT_TXT_FONT_SIZE)
        private set

    // --- Online (Office) conversion ---
    var onlineConversion by mutableStateOf<OnlineConversion?>(null)
        private set

    // --- Processing state (Screen 3 / 4) ---
    private val _state = MutableStateFlow<CompressionState>(CompressionState.Idle)
    val state: StateFlow<CompressionState> = _state.asStateFlow()

    /** Resolves PDF metadata off the main thread, then arms Screen 2. */
    fun onPdfPicked(uri: Uri) {
        viewModelScope.launch {
            val picked = withContext(Dispatchers.IO) {
                storage.resolveMetadata(uri, FileType.PDF)
            }
            setPdf(picked)
        }
    }

    /** Resolves text-file metadata off the main thread, then arms the TXT → PDF screen. */
    fun onTxtPicked(uri: Uri) {
        viewModelScope.launch {
            val picked = withContext(Dispatchers.IO) {
                storage.resolveMetadata(uri, FileType.PDF)
            }
            txtInput = picked
            txtPdfName = storage.baseNameOf(picked.displayName)
            txtFontSize = DEFAULT_TXT_FONT_SIZE
            _state.value = CompressionState.Idle
        }
    }

    fun updateTxtPdfName(name: String) {
        txtPdfName = name
    }

    fun updateTxtFontSize(sizePt: Int) {
        txtFontSize = sizePt.coerceIn(7, 24)
    }

    /** Resolves metadata for a freshly picked batch of images (replaces the current set). */
    fun onImagesPicked(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val items = withContext(Dispatchers.IO) {
                uris.take(MAX_IMAGE_SELECTION).map { toBatchItem(it) }
            }
            setImages(items)
        }
    }

    /** Appends more images to the existing batch (deduped by URI, capped at 5). */
    fun onImagesPickedAppend(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val added = withContext(Dispatchers.IO) { uris.map { toBatchItem(it) } }
            imageItems = (imageItems + added)
                .distinctBy { it.source.uriString }
                .take(MAX_IMAGE_SELECTION)
        }
    }

    private fun toBatchItem(uri: Uri): ImageBatchItem {
        val picked = storage.resolveMetadata(uri, FileType.IMAGE)
        return ImageBatchItem(source = picked, outputName = storage.baseNameOf(picked.displayName))
    }

    fun setPdf(file: PickedFile) {
        pdfInput = file
        pdfOutputName = storage.baseNameOf(file.displayName)
        selectedProfile = CompressionProfile.DEFAULT
        pdfImageFormat = ImageFormat.JPEG
        pdfImageQuality = DEFAULT_IMAGE_QUALITY
        _state.value = CompressionState.Idle
    }

    /** Updates the editable PDF output name (Screen 2 rename field). */
    fun updatePdfOutputName(name: String) {
        pdfOutputName = name
    }

    fun setProfile(profile: CompressionProfile) {
        selectedProfile = profile
    }

    fun updatePdfImageFormat(format: ImageFormat) {
        pdfImageFormat = format
    }

    fun updatePdfImageQuality(quality: Int) {
        pdfImageQuality = quality.coerceIn(10, 100)
    }

    fun setImages(items: List<ImageBatchItem>) {
        imageItems = items
        selectedFormat = ImageFormat.DEFAULT
        imageQuality = DEFAULT_IMAGE_QUALITY
        imagesToPdfName = items.firstOrNull()?.outputName ?: "images"
        imagesToPdfQuality = DEFAULT_IMAGE_QUALITY
        _state.value = CompressionState.Idle
    }

    fun updateImagesToPdfName(name: String) {
        imagesToPdfName = name
    }

    fun updateImagesToPdfQuality(quality: Int) {
        imagesToPdfQuality = quality.coerceIn(10, 100)
    }

    /** Removes one image from the batch (UI keeps at least one). */
    fun removeImageAt(index: Int) {
        imageItems = imageItems.filterIndexed { i, _ -> i != index }
    }

    /** Reorders the batch (drag-to-reorder); processing runs in this order. */
    fun moveImage(fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        imageItems = imageItems.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
    }

    /** Renames a single image's output (per-row rename in the batch editor). */
    fun updateImageNameAt(index: Int, name: String) {
        imageItems = imageItems.mapIndexed { i, item ->
            if (i == index) item.copy(outputName = name) else item
        }
    }

    fun setFormat(format: ImageFormat) {
        selectedFormat = format
    }

    fun updateImageQuality(quality: Int) {
        imageQuality = quality.coerceIn(10, 100)
    }

    /** Kicks off PDF compression on a background coroutine (Screen 3 entry). */
    fun startPdfCompression() = launchJob {
        val input = pdfInput
            ?: throw CompressionException("No document selected. Please pick a PDF first.")
        val name = pdfOutputName.ifBlank { storage.baseNameOf(input.displayName) }
        pdfCompressor.compress(input, selectedProfile, name)
    }

    /** Kicks off PDF → Images conversion on a background coroutine (Screen 3 entry). */
    fun startPdfToImages() = launchJob {
        val input = pdfInput
            ?: throw CompressionException("No document selected. Please pick a PDF first.")
        pdfToImageConverter.convert(input, pdfImageFormat, pdfImageQuality)
    }

    /** Kicks off batch image conversion on a background coroutine (Screen 3 entry). */
    fun startImageConversion() = launchJob {
        if (imageItems.isEmpty()) {
            throw CompressionException("No images selected. Please pick up to 5 images first.")
        }
        imageConverter.convert(imageItems, selectedFormat, imageQuality)
    }

    /** Combines the picked images (in current order) into a single PDF (Screen 3 entry). */
    fun startImagesToPdf() = launchJob {
        if (imageItems.isEmpty()) {
            throw CompressionException("No images selected. Please pick up to 5 images first.")
        }
        val name = imagesToPdfName.ifBlank { "images" }
        imagesToPdfConverter.convert(imageItems, name, imagesToPdfQuality)
    }

    /** Renders the picked text file into a paginated PDF (Screen 3 entry). */
    fun startTxtToPdf() = launchJob {
        val input = txtInput
            ?: throw CompressionException("No text file selected. Please pick a .txt file first.")
        val name = txtPdfName.ifBlank { storage.baseNameOf(input.displayName) }
        txtToPdfConverter.convert(input, name, txtFontSize)
    }

    /** Runs an online Office conversion on the picked file (Screen 3 entry). */
    fun startOnlineConversion(uri: Uri, conversion: OnlineConversion) = launchJob {
        onlineConversion = conversion
        val input = withContext(Dispatchers.IO) {
            storage.resolveMetadata(uri, FileType.DOCUMENT)
        }
        onlineConversionService.convert(input, conversion)
    }

    private fun launchJob(work: suspend () -> CompressionResult) {
        if (_state.value is CompressionState.Loading) return
        _state.value = CompressionState.Loading
        viewModelScope.launch {
            try {
                val result = work()
                // Local storage write routine — MUST complete before the
                // interstitial bridge / Results screen (Engineering spec §4.2).
                repository.record(result)
                _state.value = CompressionState.Success(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: CompressionException) {
                _state.value = CompressionState.Error(e.message ?: "Processing failed.")
            } catch (e: Throwable) {
                _state.value = CompressionState.Error("An unexpected error occurred. Please try again.")
            }
        }
    }

    /** Returns to Idle (e.g. after dismissing an error popup). */
    fun acknowledgeError() {
        if (_state.value is CompressionState.Error) _state.value = CompressionState.Idle
    }

    /** Full reset when returning Home so a new job starts clean. */
    fun clear() {
        pdfInput = null
        pdfOutputName = ""
        imageItems = emptyList()
        selectedProfile = CompressionProfile.DEFAULT
        pdfImageFormat = ImageFormat.JPEG
        pdfImageQuality = DEFAULT_IMAGE_QUALITY
        selectedFormat = ImageFormat.DEFAULT
        imageQuality = DEFAULT_IMAGE_QUALITY
        imagesToPdfName = ""
        imagesToPdfQuality = DEFAULT_IMAGE_QUALITY
        txtInput = null
        txtPdfName = ""
        txtFontSize = DEFAULT_TXT_FONT_SIZE
        onlineConversion = null
        _state.value = CompressionState.Idle
    }

    companion object {
        private const val DEFAULT_IMAGE_QUALITY = 80
        private const val DEFAULT_TXT_FONT_SIZE = 11
        const val MAX_IMAGE_SELECTION = 5
    }
}
