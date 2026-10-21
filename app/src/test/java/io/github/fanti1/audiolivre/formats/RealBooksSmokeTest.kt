package io.github.fanti1.audiolivre.formats

import io.github.fanti1.audiolivre.book.BookBuilder
import io.github.fanti1.audiolivre.translate.LanguageDetector
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

// Reads every book in the folder named by AUDIOLIVRE_SAMPLES (real EPUB, Kindle, FB2, Word… files) and prints what
// came out, for checking the readers against books nobody wrote by hand. Skipped when the variable is unset.
class RealBooksSmokeTest {

    @Test
    fun `real books open and have text, chapters and covers`() {
        val dir = System.getenv("AUDIOLIVRE_SAMPLES")?.let(::File)
        assumeTrue(dir != null && dir.isDirectory)
        val files = dir!!.listFiles()!!.filter { it.isFile }.sortedBy { it.name }
        for (file in files) {
            val bytes = file.readBytes()
            val format = BookFormat.detect(file.name, bytes)
            if (format == BookFormat.PDF) continue
            val book = BookReaders.read(format, bytes) { _, _ -> }
            val document = BookBuilder.fromBlocks(book.blocks, book.toc, book.title ?: file.name)
            val cover = BookReaders.cover(format, bytes)
            println(
                "${file.name} [$format] \"${book.title}\" / ${book.author} — ${book.blocks.size} blocks, " +
                    "${document.sentences.size} sentences, ${document.chapters.size} chapters, toc ${book.toc.size}, " +
                    "cover ${cover?.size ?: 0} bytes, language ${LanguageDetector.detect(document.paragraphs.map { it.text })}",
            )
            println("   first: ${book.blocks.take(4).joinToString(" | ") { (if (it.isHeading) "#" else "") + it.text.take(50) }}")
            println("   chapters: ${document.chapters.take(5).joinToString(" | ") { it.title.take(40) }}")
            println("   last: ${book.blocks.takeLast(2).joinToString(" | ") { it.text.take(60) }}")
            assertTrue("${file.name} has no text", document.sentences.size > 100)
        }
    }
}
