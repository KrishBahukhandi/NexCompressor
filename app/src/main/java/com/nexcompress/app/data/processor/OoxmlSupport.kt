package com.nexcompress.app.data.processor

import com.nexcompress.app.domain.model.CompressionException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/**
 * Shared plumbing for reading and writing Office Open XML packages
 * (.docx/.xlsx/.pptx are plain ZIP archives of XML parts).
 *
 * SECURITY: .docx/.xlsx are UNTRUSTED archives. Reads are bounded (zip-bomb
 * defense — a tiny entry can claim to decompress to gigabytes) and the XML
 * parser has DTD/entity processing disabled (entity-expansion / XXE defense).
 */
internal object OoxmlSupport {

    /** Hard cap on any single decompressed part — far above real documents,
     *  far below a decompression bomb. Enforced on ACTUAL bytes, not the
     *  (spoofable) ZIP header size. */
    private const val MAX_PART_BYTES = 64L * 1024 * 1024

    /** Returns the bytes of one part, or null when the package lacks it. */
    fun readPart(zip: ZipFile, name: String): ByteArray? {
        val entry: ZipEntry = zip.getEntry(name) ?: return null
        return zip.getInputStream(entry).use { readCapped(it) }
    }

    /** Reads [input] fully but aborts if it exceeds [MAX_PART_BYTES] (zip-bomb guard). */
    private fun readCapped(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            total += n
            if (total > MAX_PART_BYTES) {
                throw CompressionException(
                    "This document is too large or malformed to open safely."
                )
            }
            out.write(chunk, 0, n)
        }
        return out.toByteArray()
    }

    /** Returns the bytes of one part or fails with a user-facing message. */
    fun requirePart(zip: ZipFile, name: String, friendlyFormat: String): ByteArray =
        readPart(zip, name) ?: throw CompressionException(
            "This file doesn't look like a valid $friendlyFormat document."
        )

    /** Namespace-aware pull parser over a part's bytes, hardened against
     *  DTD/entity attacks (no DOCTYPE processing → no billion-laughs/XXE). */
    fun parser(bytes: ByteArray): XmlPullParser {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        factory.isValidating = false
        // Explicitly refuse to process the DOCTYPE/DTD. KXmlParser defaults to
        // this, but making it explicit prevents an entity-expansion bomb from
        // ever being introduced by a future config change.
        runCatching { factory.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false) }
        return factory.newPullParser().apply {
            setInput(ByteArrayInputStream(bytes), "UTF-8")
        }
    }

    /**
     * Parses an OPC relationships part (e.g. word/_rels/document.xml.rels) into
     * rId -> target, with relative targets resolved against [baseDir].
     */
    fun parseRelationships(bytes: ByteArray?, baseDir: String): Map<String, String> {
        bytes ?: return emptyMap()
        val rels = mutableMapOf<String, String>()
        val p = parser(bytes)
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG && p.name == "Relationship") {
                val id = p.getAttributeValue(null, "Id") ?: continue
                val target = p.getAttributeValue(null, "Target") ?: continue
                rels[id] = if (target.startsWith("/")) {
                    target.trimStart('/')
                } else {
                    "$baseDir/$target".replace("/./", "/")
                }
            }
        }
        return rels
    }

    /** Reads an attribute by local name regardless of its namespace prefix. */
    fun attr(p: XmlPullParser, localName: String): String? {
        for (i in 0 until p.attributeCount) {
            if (p.getAttributeName(i) == localName) return p.getAttributeValue(i)
        }
        return null
    }

    fun escapeXml(s: String): String = buildString(s.length) {
        for (ch in s) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                // Strip control chars that are illegal in XML 1.0.
                else -> if (ch.code >= 0x20 || ch == '\t') append(ch)
            }
        }
    }

    fun ZipOutputStream.putPart(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    fun ZipOutputStream.putPart(name: String, content: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(content)
        closeEntry()
    }

    /** Converts an "A1"-style cell reference's column letters to a 0-based index. */
    fun columnIndexOf(cellRef: String): Int {
        var col = 0
        for (ch in cellRef) {
            if (!ch.isLetter()) break
            col = col * 26 + (ch.uppercaseChar() - 'A' + 1)
        }
        return col - 1
    }

    /** Stages any content URI to a private cache file (shared with PdfFiles). */
    fun stage(context: android.content.Context, uriString: String, prefix: String): File =
        PdfFiles.copyToCache(context, android.net.Uri.parse(uriString), prefix)
}
