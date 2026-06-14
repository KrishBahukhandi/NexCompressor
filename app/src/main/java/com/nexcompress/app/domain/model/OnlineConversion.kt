package com.nexcompress.app.domain.model

/**
 * The document conversions, all of which run fully on-device (content-faithful,
 * layout simplified). Modern Office formats only — legacy .doc/.xls/.ppt can't
 * be parsed offline. Each entry carries the SAF picker MIME filter for its
 * source type plus the target extension/MIME used by the conversion engines.
 *
 * (The online-only conversions — PowerPoint → PDF and PDF → Excel, which had no
 * faithful offline path — were removed; the pluggable [RestConversionService]
 * remains in the codebase for a future keyed deployment but isn't surfaced.)
 */
enum class OnlineConversion(
    val title: String,
    val sourceMimes: List<String>,
    val defaultSourceExt: String,
    val targetExt: String,
    val targetMime: String,
    val offline: Boolean = true
) {
    WORD_TO_PDF("Word → PDF", listOf(MIME_DOCX), "docx", "pdf", MIME_PDF),
    PDF_TO_WORD("PDF → Word", listOf(MIME_PDF), "pdf", "docx", MIME_DOCX),
    EXCEL_TO_PDF("Excel → PDF", listOf(MIME_XLSX), "xlsx", "pdf", MIME_PDF),
    PDF_TO_PPT("PDF → PowerPoint", listOf(MIME_PDF), "pdf", "pptx", MIME_PPTX);

    val producesPdf: Boolean get() = targetExt == "pdf"

    companion object {
        fun fromTitle(title: String): OnlineConversion? = entries.firstOrNull { it.title == title }
    }
}

private const val MIME_PDF = "application/pdf"
private const val MIME_DOCX =
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
private const val MIME_PPTX =
    "application/vnd.openxmlformats-officedocument.presentationml.presentation"
private const val MIME_XLSX =
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
