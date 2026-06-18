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
import com.nexcompress.app.data.processor.OfficeConverter
import com.nexcompress.app.data.processor.PdfAnnotator
import com.nexcompress.app.data.processor.PdfCompressor
import com.nexcompress.app.data.processor.PdfMerger
import com.nexcompress.app.data.processor.PdfPageEditor
import com.nexcompress.app.data.processor.PdfProtector
import com.nexcompress.app.data.processor.PdfSplitter
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
import com.nexcompress.app.domain.model.ImageEditSpec
import com.nexcompress.app.domain.model.ImageFormat
import com.nexcompress.app.domain.model.OnlineConversion
import com.nexcompress.app.domain.model.PdfAnnotation
import com.nexcompress.app.domain.model.PdfPageOp
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
    private val pdfPageEditor: PdfPageEditor,
    private val pdfMerger: PdfMerger,
    private val pdfSplitter: PdfSplitter,
    private val pdfProtector: PdfProtector,
    private val pdfAnnotator: PdfAnnotator,
    private val officeConverter: OfficeConverter,
    private val onlineConversionService: OnlineConversionService
) : ViewModel() {

    /** True when a real online endpoint + key are configured. */
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

    /** Unified Images tool: false = export converted image files, true = one PDF. */
    var imagesAsPdf by mutableStateOf(false)
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

    // --- PDF page editor (reorder / rotate / delete) ---
    var pdfPagesSource by mutableStateOf<PickedFile?>(null)
        private set
    var pdfPageOps by mutableStateOf<List<PdfPageOp>>(emptyList())
        private set
    var pdfPagesName by mutableStateOf("")
        private set

    // --- Merge PDFs ---
    var mergeItems by mutableStateOf<List<PickedFile>>(emptyList())
        private set
    var mergeName by mutableStateOf("")
        private set

    // --- Split / extract PDF ---
    var splitSource by mutableStateOf<PickedFile?>(null)
        private set
    var splitName by mutableStateOf("")
        private set
    var splitPageCount by mutableStateOf(0)
        private set

    // --- Protect / unlock PDF ---
    var protectSource by mutableStateOf<PickedFile?>(null)
        private set
    var protectName by mutableStateOf("")
        private set

    // --- Annotate PDF ---
    var annotateSource by mutableStateOf<PickedFile?>(null)
        private set
    var annotateName by mutableStateOf("")
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
                storage.resolveMetadata(uri, FileType.DOCUMENT)
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
        imagesAsPdf = false
        _state.value = CompressionState.Idle
    }

    /** Toggles the unified Images tool between image-file output and a single PDF. */
    fun updateImagesAsPdf(asPdf: Boolean) {
        imagesAsPdf = asPdf
    }

    /** Stores a per-image edit (rotate / flip / crop / resize) for one batch item. */
    fun updateImageEditSpec(index: Int, spec: ImageEditSpec?) {
        imageItems = imageItems.mapIndexed { i, item ->
            if (i == index) item.copy(editSpec = spec) else item
        }
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

    /** Exports the selected PDF as a PowerPoint deck — one full-bleed slide per page. */
    fun startPdfToPptx() = launchJob {
        val input = pdfInput
            ?: throw CompressionException("No document selected. Please pick a PDF first.")
        officeConverter.convert(input, OnlineConversion.PDF_TO_PPT)
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
        // Modern formats convert on-device; legacy binary formats (.doc/.xls)
        // and the conversions without an offline engine use the online service.
        val ext = input.displayName.substringAfterLast('.', "").lowercase()
        if (conversion.offline && ext !in LEGACY_OFFICE_EXTS) {
            officeConverter.convert(input, conversion)
        } else {
            onlineConversionService.convert(input, conversion)
        }
    }

    // ===================== PDF page editor (reorder / rotate / delete) ================

    fun onPdfPagesPicked(uri: Uri) {
        viewModelScope.launch {
            val picked = withContext(Dispatchers.IO) { storage.resolveMetadata(uri, FileType.PDF) }
            pdfPagesSource = picked
            pdfPagesName = storage.baseNameOf(picked.displayName) + "-edited"
            pdfPageOps = emptyList()
            _state.value = CompressionState.Idle
            val count = pdfPageEditor.pageCount(picked)
            pdfPageOps = (0 until count).map { PdfPageOp(it, 0) }
        }
    }

    fun movePdfPage(fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        pdfPageOps = pdfPageOps.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
    }

    /** Adds a 90° clockwise turn to one page. */
    fun rotatePdfPage(index: Int) {
        pdfPageOps = pdfPageOps.mapIndexed { i, op ->
            if (i == index) op.copy(rotation = (op.rotation + 90) % 360) else op
        }
    }

    fun deletePdfPage(index: Int) {
        pdfPageOps = pdfPageOps.filterIndexed { i, _ -> i != index }
    }

    fun updatePdfPagesName(name: String) {
        pdfPagesName = name
    }

    fun startPdfPageEdit() = launchJob {
        val input = pdfPagesSource
            ?: throw CompressionException("No document selected. Please pick a PDF first.")
        val name = pdfPagesName.ifBlank { storage.baseNameOf(input.displayName) }
        pdfPageEditor.apply(input, pdfPageOps, name)
    }

    // ===================== Merge PDFs ================================================

    fun onMergePicked(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val items = withContext(Dispatchers.IO) {
                uris.take(MAX_MERGE_SELECTION).map { storage.resolveMetadata(it, FileType.PDF) }
            }
            mergeItems = items
            mergeName = items.firstOrNull()?.let { storage.baseNameOf(it.displayName) + "-merged" } ?: "merged"
            _state.value = CompressionState.Idle
        }
    }

    fun onMergePickedAppend(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val added = withContext(Dispatchers.IO) { uris.map { storage.resolveMetadata(it, FileType.PDF) } }
            mergeItems = (mergeItems + added).distinctBy { it.uriString }.take(MAX_MERGE_SELECTION)
        }
    }

    fun moveMergeItem(fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        mergeItems = mergeItems.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
    }

    fun removeMergeItemAt(index: Int) {
        mergeItems = mergeItems.filterIndexed { i, _ -> i != index }
    }

    fun updateMergeName(name: String) {
        mergeName = name
    }

    fun startMerge() = launchJob {
        if (mergeItems.size < 2) {
            throw CompressionException("Pick at least two PDFs to merge.")
        }
        pdfMerger.merge(mergeItems, mergeName.ifBlank { "merged" })
    }

    // ===================== Split / extract PDF ======================================

    fun onSplitPicked(uri: Uri) {
        viewModelScope.launch {
            val picked = withContext(Dispatchers.IO) { storage.resolveMetadata(uri, FileType.PDF) }
            splitSource = picked
            splitName = storage.baseNameOf(picked.displayName)
            splitPageCount = 0
            _state.value = CompressionState.Idle
            splitPageCount = pdfSplitter.pageCount(picked)
        }
    }

    fun updateSplitName(name: String) {
        splitName = name
    }

    /** Pulls the chosen [pageIndices] (0-based) into a single new PDF. */
    fun startSplitExtract(pageIndices: List<Int>) = launchJob {
        val input = splitSource
            ?: throw CompressionException("No document selected. Please pick a PDF first.")
        if (pageIndices.isEmpty()) throw CompressionException("Select at least one page to extract.")
        pdfSplitter.extract(input, pageIndices, splitName.ifBlank { storage.baseNameOf(input.displayName) })
    }

    /** Explodes every page into its own PDF. */
    fun startSplitEach() = launchJob {
        val input = splitSource
            ?: throw CompressionException("No document selected. Please pick a PDF first.")
        pdfSplitter.splitEach(input, splitName.ifBlank { storage.baseNameOf(input.displayName) })
    }

    // ===================== Protect / unlock PDF =====================================

    fun onProtectPicked(uri: Uri) {
        viewModelScope.launch {
            val picked = withContext(Dispatchers.IO) { storage.resolveMetadata(uri, FileType.PDF) }
            protectSource = picked
            protectName = storage.baseNameOf(picked.displayName) + "-locked"
            _state.value = CompressionState.Idle
        }
    }

    /** Base name (no extension) of the picked file, for suffix swaps in the UI. */
    fun protectSourceBaseName(): String? =
        protectSource?.let { storage.baseNameOf(it.displayName) }

    fun updateProtectName(name: String) {
        protectName = name
    }

    fun startProtect(password: String) = launchJob {
        val input = protectSource
            ?: throw CompressionException("No document selected. Please pick a PDF first.")
        val name = protectName.ifBlank { storage.baseNameOf(input.displayName) + "-locked" }
        pdfProtector.protect(input, password, name)
    }

    fun startUnlock(password: String) = launchJob {
        val input = protectSource
            ?: throw CompressionException("No document selected. Please pick a PDF first.")
        val name = protectName.ifBlank { storage.baseNameOf(input.displayName) + "-unlocked" }
        pdfProtector.unlock(input, password, name)
    }

    // ===================== Annotate PDF ============================================

    fun onAnnotatePicked(uri: Uri) {
        viewModelScope.launch {
            val picked = withContext(Dispatchers.IO) { storage.resolveMetadata(uri, FileType.PDF) }
            annotateSource = picked
            annotateName = storage.baseNameOf(picked.displayName) + "-edited"
            _state.value = CompressionState.Idle
        }
    }

    fun updateAnnotateName(name: String) {
        annotateName = name
    }

    fun startAnnotate(annotations: List<PdfAnnotation>) = launchJob {
        val input = annotateSource
            ?: throw CompressionException("No document selected. Please pick a PDF first.")
        val name = annotateName.ifBlank { storage.baseNameOf(input.displayName) + "-edited" }
        pdfAnnotator.annotate(input, annotations, name)
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
        imagesAsPdf = false
        txtInput = null
        txtPdfName = ""
        txtFontSize = DEFAULT_TXT_FONT_SIZE
        onlineConversion = null
        pdfPagesSource = null
        pdfPageOps = emptyList()
        pdfPagesName = ""
        mergeItems = emptyList()
        mergeName = ""
        splitSource = null
        splitName = ""
        splitPageCount = 0
        protectSource = null
        protectName = ""
        annotateSource = null
        annotateName = ""
        _state.value = CompressionState.Idle
    }

    companion object {
        private const val DEFAULT_IMAGE_QUALITY = 80
        private const val DEFAULT_TXT_FONT_SIZE = 11
        const val MAX_IMAGE_SELECTION = 5
        const val MAX_MERGE_SELECTION = 20
        private val LEGACY_OFFICE_EXTS = setOf("doc", "xls", "ppt")
    }
}
