package com.nexcompress.app.data.processor

import com.nexcompress.app.domain.model.CompressionResult
import com.nexcompress.app.domain.model.OnlineConversion
import com.nexcompress.app.domain.model.PickedFile

/**
 * Facade over the on-device Office conversion engines. Only the conversions
 * flagged [OnlineConversion.offline] are routed here; the rest go through the
 * optional online service.
 */
class OfficeConverter(
    private val storage: FileStorageManager,
    private val docxToPdf: DocxToPdfConverter,
    private val xlsxToPdf: XlsxToPdfConverter,
    private val pdfToDocx: PdfToDocxConverter,
    private val pdfToPptx: PdfToPptxConverter
) {

    suspend fun convert(input: PickedFile, conversion: OnlineConversion): CompressionResult {
        val baseName = storage.baseNameOf(input.displayName)
        return when (conversion) {
            OnlineConversion.WORD_TO_PDF -> docxToPdf.convert(input, baseName)
            OnlineConversion.EXCEL_TO_PDF -> xlsxToPdf.convert(input, baseName)
            OnlineConversion.PDF_TO_WORD -> pdfToDocx.convert(input, baseName)
            OnlineConversion.PDF_TO_PPT -> pdfToPptx.convert(input, baseName)
            else -> throw IllegalArgumentException("${conversion.title} has no on-device engine")
        }
    }
}
