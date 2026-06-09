package com.nexcompress.app.domain.util

import java.util.Locale

/** Human-readable formatting for byte counts shown across the UI. */
object FormatUtils {

    private val UNITS = arrayOf("B", "KB", "MB", "GB", "TB")

    /** e.g. 19_315_650 -> "18.42 MB". Used for headline metrics. */
    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        var value = bytes.toDouble()
        var i = 0
        while (value >= 1024 && i < UNITS.lastIndex) {
            value /= 1024.0
            i++
        }
        return if (i == 0) {
            "$bytes B"
        } else {
            String.format(Locale.US, "%.2f %s", value, UNITS[i])
        }
    }

    /** Compact form for dense history rows, e.g. 2_516_582 -> "2.4MB", 430_080 -> "420KB". */
    fun formatBytesCompact(bytes: Long): String {
        if (bytes <= 0) return "0B"
        var value = bytes.toDouble()
        var i = 0
        while (value >= 1024 && i < UNITS.lastIndex) {
            value /= 1024.0
            i++
        }
        val pattern = if (value >= 100 || i == 0) "%.0f%s" else "%.1f%s"
        return String.format(Locale.US, pattern, value, UNITS[i])
    }
}
