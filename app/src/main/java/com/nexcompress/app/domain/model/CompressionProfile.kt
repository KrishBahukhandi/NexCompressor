package com.nexcompress.app.domain.model

/**
 * PDF compression presets rendered as the RadioButton list on Screen 2.
 *
 * The percentage in each [badge] is the marketing-facing "compression profile"
 * exactly as written in the PRD wireframe. Internally:
 *  - [quality]     is the JPEG quality (0–100) used when re-encoding rasterized
 *                  page imagery inside the rebuilt PDF.
 *  - [renderScale] down-samples page dimensions (DPI scaling) to trade visual
 *                  fidelity for output size.
 *
 * Lower profiles => smaller output files.
 */
enum class CompressionProfile(
    val title: String,
    val subtitle: String,
    val badge: String,
    val quality: Int,
    val renderScale: Float
) {
    RECOMMENDED(
        title = "Recommended Savings",
        subtitle = "Best operational balance for text & layout preservation",
        badge = "65% Compression",
        quality = 65,
        renderScale = 0.75f
    ),
    BALANCED(
        title = "Balanced Extraction",
        subtitle = "Aggressive scaling; ideal for pure text sheets",
        badge = "40% Compression",
        quality = 40,
        renderScale = 0.55f
    ),
    HIGH_FIDELITY(
        title = "High-Fidelity Retain",
        subtitle = "Minimal compression footprint for image-heavy files",
        badge = "90% Compression",
        quality = 90,
        renderScale = 1.0f
    );

    companion object {
        val DEFAULT = RECOMMENDED
    }
}
