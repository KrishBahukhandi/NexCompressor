package com.nexcompress.app.data.remote

import com.nexcompress.app.domain.model.CompressionResult
import com.nexcompress.app.domain.model.OnlineConversion
import com.nexcompress.app.domain.model.PickedFile

/**
 * Boundary for the service-backed Office conversions (the ones without an
 * on-device engine). The app depends only on this interface, so the concrete
 * provider (or a self-hosted endpoint) is swappable.
 */
interface OnlineConversionService {

    /** True when a real endpoint + API key are configured. */
    val isConfigured: Boolean

    /**
     * Uploads [input], runs [conversion] on the service, downloads the result,
     * and saves it locally — returning the produced file(s). Runs off the main
     * thread; throws [com.nexcompress.app.domain.model.CompressionException] with
     * a user-friendly message on any failure.
     */
    suspend fun convert(input: PickedFile, conversion: OnlineConversion): CompressionResult
}
