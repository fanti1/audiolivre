package io.github.fanti1.audiolivre.formats

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

// Kindle books without DRM: MOBI, AZW and AZW3 (KF8). All are a Palm database whose first record describes the
// book and whose next records hold the text as HTML, usually compressed with PalmDOC's LZ77 variant.
internal object MobiReader {

    private const val NO_COMPRESSION = 1
    private const val PALMDOC = 2
    private const val HUFF_CDIC = 17480
    private const val EXTH_AUTHOR = 100
    private const val EXTH_COVER_OFFSET = 201
    private const val EXTH_TITLE = 503
    private const val NO_INDEX = 0xFFFFFFFFL

    private class Header(
        val records: IntArray,
        val compression: Int,
        val textLength: Int,
        val textRecordCount: Int,
        val encryption: Int,
        val encoding: Charset,
        val extraDataFlags: Int,
        val firstImage: Long,
        val fullName: String?,
        val exth: Map<Int, ByteArray>,
    )

    fun isMobi(bytes: ByteArray): Boolean {
        if (bytes.size < 68) return false
        val type = String(bytes, 60, 8, Charsets.ISO_8859_1)
        return type == "BOOKMOBI" || type == "TEXtREAd"
    }

    fun read(bytes: ByteArray, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): ImportedBook {
        val header = readHeader(bytes)
        when {
            header.encryption != 0 -> throw UnsupportedBookException("Este livro tem DRM (proteção da Amazon) e não pode ser aberto.")
            header.compression == HUFF_CDIC -> throw UnsupportedBookException("Este arquivo do Kindle usa uma compressão que o app ainda não lê. Converta para EPUB (o Calibre faz isso de graça).")
            header.compression != NO_COMPRESSION && header.compression != PALMDOC -> throw UnsupportedBookException("Este arquivo do Kindle está corrompido.")
        }
        val text = ByteArrayOutputStream(header.textLength.coerceIn(0, 64 * 1024 * 1024))
        for (r in 1..header.textRecordCount) {
            onProgress(r, header.textRecordCount)
            val record = record(bytes, header.records, r) ?: break
            val content = record.copyOf(record.size - trailingSize(record, header.extraDataFlags))
            text.write(if (header.compression == PALMDOC) decompressPalmDoc(content) else content)
        }
        // The declared length is in bytes and cuts off padding after the last record.
        val raw = text.toByteArray().let { if (header.textLength in 1 until it.size) it.copyOf(header.textLength) else it }
        val html = String(raw, header.encoding)
        val collector = BlockCollector()
        HtmlText.collect(html, collector, boldHeadings = true)
        val title = header.exth[EXTH_TITLE]?.let { String(it, header.encoding).trim() }?.ifEmpty { null } ?: header.fullName
        val author = header.exth[EXTH_AUTHOR]?.let { String(it, header.encoding).trim() }?.ifEmpty { null }
        return ImportedBook(title, author, collector.finish())
    }

    fun cover(bytes: ByteArray): ByteArray? = runCatching {
        val header = readHeader(bytes)
        val offset = header.exth[EXTH_COVER_OFFSET]?.let { u32(it, 0) } ?: return@runCatching null
        if (header.firstImage == NO_INDEX || offset == NO_INDEX) return@runCatching null
        record(bytes, header.records, (header.firstImage + offset).toInt())?.takeIf(::isImage)
    }.getOrNull()

    private fun readHeader(bytes: ByteArray): Header {
        if (!isMobi(bytes)) throw UnsupportedBookException("Este arquivo do Kindle está corrompido.")
        val count = u16(bytes, 76)
        val records = IntArray(count) { u32(bytes, 78 + it * 8).toInt() }
        val r0 = records.getOrNull(0) ?: throw UnsupportedBookException("Este arquivo do Kindle está vazio.")
        val hasMobiHeader = bytes.size > r0 + 24 && String(bytes, r0 + 16, 4, Charsets.ISO_8859_1) == "MOBI"
        val mobiLength = if (hasMobiHeader) u32(bytes, r0 + 20).toInt() else 0
        val encoding = if (hasMobiHeader && u32(bytes, r0 + 28) == 65001L) Charsets.UTF_8 else Charset.forName("windows-1252")
        val exth = HashMap<Int, ByteArray>()
        var fullName: String? = null
        if (hasMobiHeader) {
            val flags = u32(bytes, r0 + 0x80)
            if (flags and 0x40L != 0L) readExth(bytes, r0 + 16 + mobiLength, exth)
            val nameOffset = u32(bytes, r0 + 0x54).toInt()
            val nameLength = u32(bytes, r0 + 0x58).toInt()
            if (nameLength in 1..1024 && r0 + nameOffset + nameLength <= bytes.size) {
                fullName = String(bytes, r0 + nameOffset, nameLength, encoding).trim().ifEmpty { null }
            }
        }
        return Header(
            records = records,
            compression = u16(bytes, r0),
            textLength = u32(bytes, r0 + 4).toInt(),
            textRecordCount = u16(bytes, r0 + 8),
            encryption = u16(bytes, r0 + 12),
            encoding = encoding,
            extraDataFlags = if (hasMobiHeader && mobiLength >= 0xE4) u16(bytes, r0 + 0xF2) else 0,
            firstImage = if (hasMobiHeader) u32(bytes, r0 + 0x6C) else NO_INDEX,
            fullName = fullName,
            exth = exth,
        )
    }

    private fun readExth(bytes: ByteArray, at: Int, into: MutableMap<Int, ByteArray>) {
        if (at + 12 > bytes.size || String(bytes, at, 4, Charsets.ISO_8859_1) != "EXTH") return
        val count = u32(bytes, at + 8).toInt()
        var p = at + 12
        repeat(count) {
            if (p + 8 > bytes.size) return
            val type = u32(bytes, p).toInt()
            val length = u32(bytes, p + 4).toInt()
            if (length < 8 || p + length > bytes.size) return
            into.putIfAbsent(type, bytes.copyOfRange(p + 8, p + length))
            p += length
        }
    }

    private fun record(bytes: ByteArray, records: IntArray, index: Int): ByteArray? {
        val start = records.getOrNull(index) ?: return null
        val end = records.getOrNull(index + 1) ?: bytes.size
        if (start < 0 || end > bytes.size || end <= start) return null
        return bytes.copyOfRange(start, end)
    }

    // Each text record may end with extra data (indexing entries, multibyte overlap) that is not text; the
    // flags in the MOBI header say which kinds are there, and each one encodes its own size.
    internal fun trailingSize(record: ByteArray, flags: Int): Int {
        var size = 0
        var f = flags shr 1
        while (f != 0) {
            if (f and 1 != 0) size += backwardVarint(record, record.size - size)
            f = f shr 1
        }
        if (flags and 1 != 0 && record.size - size - 1 >= 0) size += (record[record.size - size - 1].toInt() and 0x3) + 1
        return size.coerceAtMost(record.size)
    }

    private fun backwardVarint(record: ByteArray, end: Int): Int {
        var result = 0
        var shift = 0
        var p = end
        while (p > 0) {
            val v = record[p - 1].toInt() and 0xFF
            result = result or ((v and 0x7F) shl shift)
            shift += 7
            p--
            if (v and 0x80 != 0 || shift >= 28) break
        }
        return result
    }

    // PalmDOC: bytes 1–8 announce that many literal bytes; 0x80–0xBF start a two-byte back-reference (11-bit
    // distance, 3-bit length); 0xC0–0xFF are a space followed by an ASCII character; anything else is literal.
    internal fun decompressPalmDoc(data: ByteArray): ByteArray {
        var buffer = ByteArray(data.size * 3 + 16)
        var size = 0
        fun put(b: Int) {
            if (size == buffer.size) buffer = buffer.copyOf(buffer.size * 2)
            buffer[size++] = b.toByte()
        }
        var i = 0
        while (i < data.size) {
            val c = data[i++].toInt() and 0xFF
            when {
                c in 1..8 -> repeat(c) { if (i < data.size) put(data[i++].toInt()) }
                c < 0x80 -> put(c)
                c >= 0xC0 -> {
                    put(' '.code)
                    put(c xor 0x80)
                }
                else -> {
                    if (i >= data.size) break
                    val pair = (c shl 8) or (data[i++].toInt() and 0xFF)
                    val distance = (pair and 0x3FFF) shr 3
                    val length = (pair and 0x7) + 3
                    if (distance == 0 || distance > size) continue
                    repeat(length) { put(buffer[size - distance].toInt()) }
                }
            }
        }
        return buffer.copyOf(size)
    }

    private fun isImage(bytes: ByteArray): Boolean = bytes.size > 4 && (
        (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) ||
            (bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte()) ||
            (bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte())
        )

    private fun u16(bytes: ByteArray, p: Int): Int =
        if (p + 2 > bytes.size) 0 else ((bytes[p].toInt() and 0xFF) shl 8) or (bytes[p + 1].toInt() and 0xFF)

    private fun u32(bytes: ByteArray, p: Int): Long =
        if (p + 4 > bytes.size) 0L else (u16(bytes, p).toLong() shl 16) or u16(bytes, p + 2).toLong()
}
