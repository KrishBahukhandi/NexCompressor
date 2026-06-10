package com.nexcompress.app.domain.model

/**
 * Where a drawn signature sits on a page, normalized (0..1) over the *displayed*
 * (upright) page with the origin at the top-left. [PdfSigner] maps this onto the
 * rendered page bitmap, so the on-screen placement matches the exported PDF.
 */
data class SignaturePlacement(
    val pageIndex: Int,
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float
)
