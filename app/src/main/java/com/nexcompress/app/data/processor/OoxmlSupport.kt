package com.nexcompress.app.data.processor

import com.nexcompress.app.domain.model.CompressionException
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/**
 * Shared plumbing for reading and writing Office Open XML packages
 * (.docx/.xlsx/.pptx are plain ZIP archives of XML parts).
 */
internal object OoxmlSupport {

    /** Returns the bytes of one part, or null when the package lacks it. */
    fun readPart(zip: ZipFile, name: String): ByteArray? {
        val entry: ZipEntry = zip.getEntry(name) ?: return null
        return zip.getInputStream(entry).use { it.readBytes() }
    }

    /** Returns the bytes of one part or fails with a user-facing message. */
    fun requirePart(zip: ZipFile, name: String, friendlyFormat: String): ByteArray =
        readPart(zip, name) ?: throw CompressionException(
            "This file doesn't look like a valid $friendlyFormat document."
        )

    /** Namespace-aware pull parser over a part's bytes. */
    fun parser(bytes: ByteArray): XmlPullParser {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
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
