package io.github.fanti1.audiolivre.formats

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

// Files arrive in whatever encoding the tool that made them liked: UTF-8 with or without a BOM, UTF-16 from
// Windows, Latin-1/Windows-1252 from older Brazilian sites and editors. A wrong guess turns every "ç" into
// "Ã§", which the voice then reads letter by letter.
internal object TextDecoding {

    private val WINDOWS_1252: Charset = Charset.forName("windows-1252")
    private val XML_ENCODING = Regex("""<\?xml[^>]*encoding\s*=\s*["']([\w.:-]+)["']""", RegexOption.IGNORE_CASE)
    private val META_CHARSET = Regex("""<meta[^>]+charset\s*=\s*["']?([\w.:-]+)""", RegexOption.IGNORE_CASE)

    fun decode(bytes: ByteArray, declared: String? = null): String {
        bomCharset(bytes)?.let { (charset, skip) -> return String(bytes, skip, bytes.size - skip, charset) }
        declared?.let { name -> runCatching { Charset.forName(name) }.getOrNull() }?.let { return String(bytes, it) }
        return strictUtf8(bytes) ?: String(bytes, WINDOWS_1252)
    }

    // XML and HTML say their own encoding in the first few hundred bytes, which are ASCII in every encoding
    // that matters here.
    fun decodeMarkup(bytes: ByteArray): String {
        val head = String(bytes, 0, minOf(bytes.size, 1024), Charsets.ISO_8859_1)
        val declared = XML_ENCODING.find(head)?.groupValues?.get(1) ?: META_CHARSET.find(head)?.groupValues?.get(1)
        return decode(bytes, declared)
    }

    private fun bomCharset(bytes: ByteArray): Pair<Charset, Int>? = when {
        bytes.startsWith(0xEF, 0xBB, 0xBF) -> Charsets.UTF_8 to 3
        bytes.startsWith(0xFF, 0xFE) -> Charsets.UTF_16LE to 2
        bytes.startsWith(0xFE, 0xFF) -> Charsets.UTF_16BE to 2
        else -> null
    }

    private fun strictUtf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        null
    }

    private fun ByteArray.startsWith(vararg prefix: Int): Boolean =
        size >= prefix.size && prefix.indices.all { (this[it].toInt() and 0xFF) == prefix[it] }
}
