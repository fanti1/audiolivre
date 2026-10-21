package io.github.fanti1.audiolivre.book

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

// A prepared book on disk, in a compact binary form read as a stream. The JSON it replaces had to be read whole
// into one string and parsed into a tree of objects before the book existed: for a 5 MB novel, some 27 MB of
// memory and a garbage collection on every opening. Here each paragraph is read straight into place, numbers are
// variable-length, and what can be worked out again (each sentence's position in the book and its spoken text)
// is not written at all.
object BookStore {

    // Bump when the layout below or BookBuilder's output changes; an older file is then read as missing.
    private const val MAGIC = 0x414C4252 // "ALBR"
    private const val VERSION = 1
    private const val BUFFER = 64 * 1024

    fun write(file: File, book: BookDocument, source: SourceStamp) {
        DataOutputStream(BufferedOutputStream(file.outputStream(), BUFFER)).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeLong(source.size)
            out.writeLong(source.lastModified)
            out.writeText(book.title)

            out.writeVarint(book.chapters.size)
            for (chapter in book.chapters) {
                out.writeText(chapter.title)
                out.writeVarint(chapter.firstParagraph)
            }

            out.writeVarint(book.paragraphs.size)
            for (p in book.paragraphs) {
                out.writeText(p.text)
                out.writeSignedVarint(p.chapterIndex)
                out.writeBoolean(p.isHeading)
                out.writeSignedVarint(p.sourcePage)
                out.writeVarint(p.segments.size)
                for (s in p.segments) {
                    out.writeByte(packKind(s.kind, s.genderHint))
                    out.writeVarint(s.start)
                    out.writeVarint(s.end - s.start)
                }
            }

            // Sentences come in paragraph order, so each one is stored relative to the one before it.
            out.writeVarint(book.sentences.size)
            var lastParagraph = 0
            var lastPage = 0
            for (s in book.sentences) {
                out.writeVarint(s.paragraphIndex - lastParagraph)
                out.writeVarint(s.startInParagraph)
                out.writeVarint(s.endInParagraph - s.startInParagraph)
                out.writeByte(packKind(s.kind, s.genderHint))
                out.writeSignedVarint(s.sourcePage - lastPage)
                lastParagraph = s.paragraphIndex
                lastPage = s.sourcePage
            }
        }
    }

    // The book, or null when the file is missing, from another format version, cut short, or made from a
    // different version of the source file.
    fun read(file: File, expected: SourceStamp): BookDocument? = readFile(file, expected)

    // For measurements: the book whatever source file it came from, in either format.
    fun readUnchecked(file: File): BookDocument? =
        if (file.name.endsWith(".json")) BookJson.decodeUnchecked(file.readText()) else readFile(file, null)

    private fun readFile(file: File, expected: SourceStamp?): BookDocument? {
        if (!file.isFile) return null
        return try {
            DataInputStream(BufferedInputStream(file.inputStream(), BUFFER)).use { input ->
                if (input.readInt() != MAGIC || input.readInt() != VERSION) return null
                val size = input.readLong()
                val modified = input.readLong()
                if (expected != null && (size != expected.size || modified != expected.lastModified)) return null
                readBook(input)
            }
        } catch (e: IOException) {
            null
        }
    }

    private fun readBook(input: DataInputStream): BookDocument {
        val title = input.readText()
        val chapters = List(input.readVarint()) { Chapter(input.readText(), input.readVarint()) }

        val paragraphCount = input.readVarint()
        val paragraphs = ArrayList<Paragraph>(paragraphCount)
        repeat(paragraphCount) {
            val text = input.readText()
            val chapter = input.readSignedVarint()
            val heading = input.readBoolean()
            val page = input.readSignedVarint()
            val segments = List(input.readVarint()) {
                val packed = input.readUnsignedByte()
                val start = input.readVarint()
                Segment(kindOf(packed), start, start + input.readVarint(), genderOf(packed))
            }
            paragraphs += Paragraph(text, chapter, heading, page, segments)
        }

        val starts = IntArray(paragraphs.size + 1)
        for (p in paragraphs.indices) starts[p + 1] = starts[p] + paragraphs[p].text.length + 1

        val sentenceCount = input.readVarint()
        val sentences = ArrayList<BookSentence>(sentenceCount)
        var paragraph = 0
        var page = 0
        repeat(sentenceCount) { i ->
            paragraph += input.readVarint()
            val start = input.readVarint()
            val end = start + input.readVarint()
            val packed = input.readUnsignedByte()
            page += input.readSignedVarint()
            sentences += BookSentence(
                index = i,
                paragraphIndex = paragraph,
                startInParagraph = start,
                endInParagraph = end,
                kind = kindOf(packed),
                genderHint = genderOf(packed),
                sourcePage = page,
                charOffset = starts[paragraph] + start,
                paragraphText = paragraphs[paragraph].text,
            )
        }
        return BookDocument(title, chapters, paragraphs, sentences)
    }

    private fun packKind(kind: SegmentKind, gender: GenderHint) = kind.ordinal shl 4 or gender.ordinal
    private fun kindOf(packed: Int) = SegmentKind.entries[packed shr 4]
    private fun genderOf(packed: Int) = GenderHint.entries[packed and 0xF]

    // Text as its UTF-8 length and bytes: writeUTF stops at 64 KB, and a paragraph can be longer.
    private fun DataOutputStream.writeText(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        writeVarint(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readText(): String {
        val bytes = ByteArray(readVarint())
        readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    // Seven bits per byte, small numbers in one byte: most of a book's numbers are offsets inside a paragraph.
    private fun OutputStream.writeVarint(value: Int) {
        require(value >= 0) { "negative varint $value" }
        var v = value
        while (v >= 0x80) {
            write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        write(v)
    }

    private fun OutputStream.writeSignedVarint(value: Int) = writeVarint((value shl 1) xor (value shr 31))

    private fun InputStream.readVarint(): Int {
        var result = 0
        var shift = 0
        while (true) {
            val b = read()
            if (b < 0) throw EOFException()
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            if (shift > 28) throw IOException("varint too long")
        }
    }

    private fun InputStream.readSignedVarint(): Int {
        val v = readVarint()
        return (v ushr 1) xor -(v and 1)
    }
}
