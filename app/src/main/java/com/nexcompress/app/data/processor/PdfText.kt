package com.nexcompress.app.data.processor

internal object PdfText {
    /**
     * Maps [s] to characters the built-in WinAnsi fonts (Helvetica) can always
     * encode — printable ASCII — replacing anything else with a space. Lets us
     * draw arbitrary user / OCR text with a standard font without bundling one.
     */
    fun asciiSafe(s: String): String =
        buildString { for (c in s) append(if (c.code in 0x20..0x7E) c else ' ') }
}
