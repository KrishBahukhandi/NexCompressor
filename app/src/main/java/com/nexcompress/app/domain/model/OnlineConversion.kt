package com.nexcompress.app.domain.model

/**
 * The Office conversions that require an online service (no faithful offline path).
 * Each entry carries the SAF picker MIME filter for its source type plus the
 * target extension/MIME used by the conversion client.
 */
enum class OnlineConversion(
    val title: String,
    val sourceMimes: List<String>,
    val defaultSourceExt: String,
    val targetExt: String,
    val targetMime: String
) {
    WORD_TO_PDF("Word → PDF", listOf(MIME_DOCX, MIME_DOC), "docx", "pdf", MIME_PDF),
    PDF_TO_WORD("PDF → Word", listOf(MIME_PDF), "pdf", "docx", MIME_DOCX),
    PPT_TO_PDF("PowerPoint → PDF", listOf(MIME_PPTX, MIME_PPT), "pptx", "pdf", MIME_PDF),
    EXCEL_TO_PDF("Excel → PDF", listOf(MIME_XLSX, MIME_XLS), "xlsx", "pdf", MIME_PDF),
    PDF_TO_PPT("PDF → PowerPoint", listOf(MIME_PDF), "pdf", "pptx", MIME_PPTX),
    PDF_TO_EXCEL("PDF → Excel", listOf(MIME_PDF), "pdf", "xlsx", MIME_XLSX);

    val producesPdf: Boolean get() = targetExt == "pdf"

    companion object {
        fun fromTitle(title: String): OnlineConversion? = entries.firstOrNull { it.title == title }
    }
}

private const val MIME_PDF = "application/pdf"
private const val MIME_DOCX =
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
private const val MIME_DOC = "application/msword"
private const val MIME_PPTX =
    "application/vnd.openxmlformats-officedocument.presentationml.presentation"
private const val MIME_PPT = "application/vnd.ms-powerpoint"
private const val MIME_XLSX =
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
private const val MIME_XLS = "application/vnd.ms-excel"
