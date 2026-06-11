package com.nexcompress.app.domain.model

/**
 * PDF compression presets rendered as the RadioButton list on Screen 2.
 *
 * The percentage in each [badge] is the marketing-facing "compression profile"
 * exactly as written in the PRD wireframe. Internally:
 *  - [quality]        is the JPEG quality (0–100) applied when re-encoding the
 *                     images embedded in the document.
 *  - [maxImageEdgePx] caps the longest edge of embedded images (oversized scans
 *                     and photos are down-sampled to this before re-encoding).
 *                     Also used as the page raster size by the fallback pipeline
 *                     for documents PDFBox cannot parse.
 *
 * Text and vector content are never rasterized, so they stay sharp at any zoom;
 * lower profiles only squeeze the imagery harder.
 */
enum class CompressionProfile(
    val title: String,
    val subtitle: String,
    val badge: String,
    val quality: Int,
    val maxImageEdgePx: Int
) {
    RECOMMENDED(
        title = "Recommended Savings",
        subtitle = "Recompresses photos & scans; text stays sharp",
        badge = "65% Compression",
        quality = 58,
        maxImageEdgePx = 1600
    ),
    BALANCED(
        title = "Balanced Extraction",
        subtitle = "Strongest squeeze on imagery for the smallest file",
        badge = "40% Compression",
        quality = 42,
        maxImageEdgePx = 1200
    ),
    HIGH_FIDELITY(
        title = "High-Fidelity Retain",
        subtitle = "Gentle image recompression, near-original quality",
        badge = "90% Compression",
        quality = 85,
        maxImageEdgePx = 2400
    );

    companion object {
        val DEFAULT = RECOMMENDED
    }
}
