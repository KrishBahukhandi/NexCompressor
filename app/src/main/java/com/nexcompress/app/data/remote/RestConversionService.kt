package com.nexcompress.app.data.remote

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.nexcompress.app.BuildConfig
import com.nexcompress.app.data.processor.FileStorageManager
import com.nexcompress.app.domain.model.CompressionException
import com.nexcompress.app.domain.model.CompressionResult
import com.nexcompress.app.domain.model.FileType
import com.nexcompress.app.domain.model.OnlineConversion
import com.nexcompress.app.domain.model.OutputItem
import com.nexcompress.app.domain.model.PickedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder

/**
 * Provider-agnostic conversion client modelled on a ConvertAPI-style contract:
 *   POST {BASE}/convert/{from}/to/{to}?Secret={KEY}   (multipart "File")
 *     -> JSON { "Files": [ { "Url": "https://.../out.ext" } ] }
 *   GET  {Url}  -> the converted file bytes
 *
 * Endpoint + key come from BuildConfig (see app/build.gradle.kts). With no key
 * configured, the conversions without an on-device engine fail fast with a
 * clear message instead of producing placeholder output.
 */
class RestConversionService(
    private val context: Context,
    private val storage: FileStorageManager
) : OnlineConversionService {

    private val baseUrl: String get() = BuildConfig.ONLINE_CONVERT_BASE_URL.trimEnd('/')
    private val apiKey: String get() = BuildConfig.ONLINE_CONVERT_API_KEY

    override val isConfigured: Boolean
        get() = apiKey.isNotBlank() && baseUrl.isNotBlank()

    override suspend fun convert(
        input: PickedFile,
        conversion: OnlineConversion
    ): CompressionResult = withContext(Dispatchers.IO) {
        if (!isConfigured) {
            throw CompressionException(
                "${conversion.title} needs the online conversion service, which isn't " +
                    "set up in this build. (Most conversions run fully on-device — " +
                    "this one has no faithful offline equivalent.)"
            )
        }
        realConvert(input, conversion)
    }

    // ---- Real network conversion -------------------------------------------

    private fun realConvert(input: PickedFile, conversion: OnlineConversion): CompressionResult {
        val srcUri = Uri.parse(input.uriString)
        val fromExt = extensionOf(input.displayName).ifBlank { conversion.defaultSourceExt }
        // StoreFile=true makes the service reply with a download Url; without it
        // the converted file comes back inline as base64 FileData (handled too).
        val endpoint = "$baseUrl/convert/$fromExt/to/${conversion.targetExt}" +
            "?Secret=${URLEncoder.encode(apiKey, "UTF-8")}&StoreFile=true"
        val boundary = "----NexCompress${System.currentTimeMillis()}"

        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 180_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }

        try {
            // --- Upload the file as multipart/form-data ---
            connection.outputStream.use { out ->
                val header = buildString {
                    append("--").append(boundary).append("\r\n")
                    append("Content-Disposition: form-data; name=\"File\"; filename=\"")
                        .append(input.displayName).append("\"\r\n")
                    append("Content-Type: application/octet-stream\r\n\r\n")
                }
                out.write(header.toByteArray(Charsets.UTF_8))
                context.contentResolver.openInputStream(srcUri)?.use { it.copyTo(out) }
                    ?: throw CompressionException("Couldn't read the selected file.")
                out.write("\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8))
                out.flush()
            }

            val code = connection.responseCode
            if (code !in 200..299) {
                val err = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                Log.w(TAG, "Conversion HTTP $code: $err")
                throw CompressionException(serverErrorMessage(code))
            }

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val converted = parseResultFile(body)
                ?: throw CompressionException("The conversion service returned an unexpected response.")

            val outName = storage.composeOutputName(
                storage.baseNameOf(input.displayName),
                conversion.targetExt
            )
            val saved = storage.writeOutput(outName, conversion.targetMime) { os ->
                when {
                    converted.url != null -> download(converted.url, os)
                    converted.base64Data != null ->
                        os.write(Base64.decode(converted.base64Data, Base64.DEFAULT))
                }
            }

            val type = if (conversion.producesPdf) FileType.PDF else FileType.DOCUMENT
            return CompressionResult(
                listOf(
                    OutputItem(
                        displayName = outName,
                        originalSize = input.sizeBytes,
                        outputSize = saved.sizeBytes,
                        uri = saved.uri.toString(),
                        type = type
                    )
                )
            )
        } catch (e: CompressionException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw CompressionException("The conversion timed out. Please try again.")
        } catch (e: IOException) {
            throw CompressionException("Network error — couldn't reach the conversion service.")
        } finally {
            connection.disconnect()
        }
    }

    /** A converted file is delivered either by download Url or inline base64. */
    private data class ConvertedFile(val url: String?, val base64Data: String?)

    private fun parseResultFile(body: String): ConvertedFile? = try {
        val first = JSONObject(body).optJSONArray("Files")?.optJSONObject(0)
        val url = first?.optString("Url")?.takeIf { it.isNotBlank() }
        val data = first?.optString("FileData")?.takeIf { it.isNotBlank() }
        if (url == null && data == null) null else ConvertedFile(url, data)
    } catch (e: Exception) {
        null
    }

    private fun download(urlString: String, out: OutputStream) {
        val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 180_000
        }
        try {
            if (connection.responseCode !in 200..299) {
                throw CompressionException("Couldn't download the converted file.")
            }
            connection.inputStream.use { it.copyTo(out) }
        } finally {
            connection.disconnect()
        }
    }

    private fun serverErrorMessage(code: Int): String = when (code) {
        401, 403 -> "The conversion service rejected the request (check your API key)."
        413 -> "That file is too large for the conversion service."
        429 -> "Conversion rate limit reached — please try again shortly."
        in 500..599 -> "The conversion service is temporarily unavailable. Try again later."
        else -> "The conversion failed (server returned $code)."
    }

    private fun extensionOf(name: String): String =
        name.substringAfterLast('.', "").lowercase()

    companion object {
        private const val TAG = "RestConversionService"
    }
}
