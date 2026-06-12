package com.nexcompress.app.domain.model

/**
 * The Office conversions. Entries with [offline] = true run fully on-device
 * (content-faithful, layout simplified); the rest need the optional online
 * service. Each entry carries the SAF picker MIME filter for its source type
 * plus the target extension/MIME used by the conversion engines.
 */
enum class OnlineConversion(
    val title: String,
    val sourceMimes: List<String>,
    val defaultSourceExt: String,
    val targetExt: String,
    val targetMime: String,
    val offline: Boolean
) {
    WORD_TO_PDF("Word → PDF", listOf(MIME_DOCX, MIME_DOC), "docx", "pdf", MIME_PDF, offline = true),
    PDF_TO_WORD("PDF → Word", listOf(MIME_PDF), "pdf", "docx", MIME_DOCX, offline = true),
    PPT_TO_PDF("PowerPoint → PDF", listOf(MIME_PPTX, MIME_PPT), "pptx", "pdf", MIME_PDF, offline = false),
    EXCEL_TO_PDF("Excel → PDF", listOf(MIME_XLSX, MIME_XLS), "xlsx", "pdf", MIME_PDF, offline = true),
    PDF_TO_PPT("PDF → PowerPoint", listOf(MIME_PDF), "pdf", "pptx", MIME_PPTX, offline = true),
    PDF_TO_EXCEL("PDF → Excel", listOf(MIME_PDF), "pdf", "xlsx", MIME_XLSX, offline = false);

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
