package com.nexcompress.app.data.processor

import android.content.Context
import android.net.Uri
import com.nexcompress.app.domain.model.CompressionException
import com.nexcompress.app.domain.model.CompressionResult
import com.nexcompress.app.domain.model.FileType
import com.nexcompress.app.domain.model.OutputItem
import com.nexcompress.app.domain.model.PickedFile
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Password-protects (encrypts) or unlocks (decrypts) a PDF with AES-128 standard
 * security via the PDFBox engine — entirely on-device, so the password never
 * leaves the phone.
 */
class PdfProtector(
    private val context: Context,
    private val storage: FileStorageManager
) {

    /** Encrypts the PDF so it requires [password] to open. */
    suspend fun protect(
        source: PickedFile,
        password: String,
        outputBaseName: String
    ): CompressionResult = withContext(Dispatchers.IO) {
        if (password.isBlank()) throw CompressionException("Enter a password to lock this PDF.")
        val temp = PdfFiles.copyToCache(context, Uri.parse(source.uriString), "lock_")
        var doc: PDDocument? = null
        try {
            doc = PDDocument.load(temp, MemoryUsageSetting.setupTempFileOnly())
            if (doc.isEncrypted) {
                throw CompressionException("This PDF is already password-protected. Unlock it first.")
            }
            val permissions = AccessPermission()
            val policy = StandardProtectionPolicy(password, password, permissions).apply {
                encryptionKeyLength = 128
            }
            doc.protect(policy)
            save(doc, outputBaseName, source)
        } catch (e: InvalidPasswordException) {
            throw CompressionException("This PDF is already password-protected. Unlock it first.")
        } catch (oom: OutOfMemoryError) {
            throw CompressionException("This PDF is too large to process on this device.")
        } catch (e: CompressionException) {
            throw e
        } catch (e: Exception) {
            throw CompressionException("Couldn't lock this PDF.")
        } finally {
            runCatching { doc?.close() }
            temp.delete()
        }
    }

    /** Removes encryption from a protected PDF, given its [password]. */
    suspend fun unlock(
        source: PickedFile,
        password: String,
        outputBaseName: String
    ): CompressionResult = withContext(Dispatchers.IO) {
        val temp = PdfFiles.copyToCache(context, Uri.parse(source.uriString), "unlock_")
        var doc: PDDocument? = null
        try {
            doc = PDDocument.load(temp, password, MemoryUsageSetting.setupTempFileOnly())
            if (!doc.isEncrypted) {
                throw CompressionException("This PDF isn't password-protected.")
            }
            doc.setAllSecurityToBeRemoved(true)
            save(doc, outputBaseName, source)
        } catch (e: InvalidPasswordException) {
            throw CompressionException("Wrong password. Please check it and try again.")
        } catch (oom: OutOfMemoryError) {
            throw CompressionException("This PDF is too large to process on this device.")
        } catch (e: CompressionException) {
            throw e
        } catch (e: Exception) {
            throw CompressionException("Couldn't unlock this PDF.")
        } finally {
            runCatching { doc?.close() }
            temp.delete()
        }
    }

    private fun save(doc: PDDocument, outputBaseName: String, source: PickedFile): CompressionResult {
        val outName = storage.composeOutputName(outputBaseName, "pdf")
        val saved = storage.writeOutput(outName, "application/pdf") { os -> doc.save(os) }
        return CompressionResult(
            listOf(
                OutputItem(
                    displayName = outName,
                    originalSize = source.sizeBytes,
                    outputSize = saved.sizeBytes,
                    uri = saved.uri.toString(),
                    type = FileType.PDF
                )
            )
        )
    }
}
