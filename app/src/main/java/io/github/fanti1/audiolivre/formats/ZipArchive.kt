package io.github.fanti1.audiolivre.formats

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

private const val END_OF_CENTRAL_DIRECTORY = 0x06054b50L
private const val CENTRAL_DIRECTORY_ENTRY = 0x02014b50L
private const val LOCAL_HEADER = 0x04034b50L
private const val STORED = 0
private const val DEFLATED = 8
// Text and a cover are all a book needs from its archive; anything bigger is a video or a huge scan.
private const val MAX_ENTRY_BYTES = 64 * 1024 * 1024

// EPUB, DOCX, ODT and zipped FB2 are all ZIP archives. Reading the central directory straight from memory
// lets a book inflate only the few entries it needs, where a ZipInputStream would inflate every image on
// the way to the text.
internal class ZipArchive(private val bytes: ByteArray) {

    private class Entry(val method: Int, val compressedSize: Int, val size: Int, val localOffset: Int)

    private val entries: Map<String, Entry> = readCentralDirectory()

    val names: Set<String> get() = entries.keys

    fun contains(name: String) = name in entries

    fun read(name: String): ByteArray? {
        val entry = entries[name] ?: return null
        if (entry.size > MAX_ENTRY_BYTES) return null
        val header = entry.localOffset
        if (header + 30 > bytes.size || u32(header) != LOCAL_HEADER) return null
        val data = header + 30 + u16(header + 26) + u16(header + 28)
        if (data + entry.compressedSize > bytes.size) return null
        return when (entry.method) {
            STORED -> bytes.copyOfRange(data, data + entry.compressedSize)
            DEFLATED -> inflate(data, entry.compressedSize, entry.size)
            else -> null
        }
    }

    fun readText(name: String): String? = read(name)?.let(TextDecoding::decodeMarkup)

    private fun inflate(offset: Int, length: Int, expected: Int): ByteArray? {
        val inflater = Inflater(true)
        return try {
            inflater.setInput(bytes, offset, length)
            val out = ByteArrayOutputStream(expected.coerceIn(1024, MAX_ENTRY_BYTES))
            val buffer = ByteArray(64 * 1024)
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(buffer, 0, n)
                if (out.size() > MAX_ENTRY_BYTES) return null
            }
            out.toByteArray()
        } catch (e: Exception) {
            null
        } finally {
            inflater.end()
        }
    }

    private fun readCentralDirectory(): Map<String, Entry> {
        val end = findEndOfCentralDirectory() ?: throw UnsupportedBookException("O arquivo está corrompido ou incompleto.")
        val count = u16(end + 10)
        var p = u32(end + 16).toInt()
        val result = LinkedHashMap<String, Entry>()
        for (i in 0 until count) {
            if (p < 0 || p + 46 > bytes.size || u32(p) != CENTRAL_DIRECTORY_ENTRY) break
            val nameLength = u16(p + 28)
            if (p + 46 + nameLength > bytes.size) break
            val name = String(bytes, p + 46, nameLength, Charsets.UTF_8)
            result[name] = Entry(
                method = u16(p + 10),
                compressedSize = u32(p + 20).toInt(),
                size = u32(p + 24).toInt(),
                localOffset = u32(p + 42).toInt(),
            )
            p += 46 + nameLength + u16(p + 30) + u16(p + 32)
        }
        return result
    }

    private fun findEndOfCentralDirectory(): Int? {
        val lowest = maxOf(0, bytes.size - 22 - 0xFFFF)
        for (p in bytes.size - 22 downTo lowest) if (u32(p) == END_OF_CENTRAL_DIRECTORY) return p
        return null
    }

    private fun u16(p: Int): Int = (bytes[p].toInt() and 0xFF) or ((bytes[p + 1].toInt() and 0xFF) shl 8)

    private fun u32(p: Int): Long = (u16(p).toLong()) or (u16(p + 2).toLong() shl 16)

    companion object {
        fun isZip(bytes: ByteArray): Boolean =
            bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() &&
                bytes[2] == 3.toByte() && bytes[3] == 4.toByte()
    }
}
