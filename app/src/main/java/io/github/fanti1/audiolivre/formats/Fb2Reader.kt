package io.github.fanti1.audiolivre.formats

import java.util.Base64

// FictionBook 2: one XML file (sometimes zipped) with the metadata, the text in nested <section>s with their
// own <title>s, and images embedded as base64 <binary> elements at the end.
internal object Fb2Reader {

    private val PARAGRAPHS = setOf("p", "v", "subtitle", "text-author", "td", "th")

    fun read(bytes: ByteArray): ImportedBook {
        val xml = TextDecoding.decodeMarkup(unzipped(bytes))
        val collector = BlockCollector()
        val toc = mutableListOf<TocEntry>()
        var title: String? = null
        val author = StringBuilder()
        var inTitleInfo = false
        var inAuthor = false
        var capture: String? = null
        val captured = StringBuilder()
        var bodyDepth = 0
        var skippedBody = false
        var sectionDepth = 0
        var titleDepth = 0
        var titleStart = 0
        var subtitle = false

        Markup.scan(xml) { event ->
            when (event) {
                is MarkupEvent.Start -> when (event.name) {
                    "title-info" -> inTitleInfo = true
                    "author" -> if (inTitleInfo && author.isEmpty()) inAuthor = true
                    "book-title", "first-name", "middle-name", "last-name" -> if (inTitleInfo && !event.selfClosing) {
                        capture = event.name
                        captured.setLength(0)
                    }
                    "body" -> {
                        bodyDepth++
                        // Later bodies hold the footnotes and comments, which a listener doesn't want read in a row.
                        skippedBody = event["name"] != null
                    }
                    "section" -> if (bodyDepth > 0 && !skippedBody) {
                        collector.breakBlock()
                        sectionDepth++
                        event["id"]?.let(collector::anchor)
                    }
                    "title" -> if (bodyDepth > 0 && !skippedBody) {
                        collector.startHeading()
                        titleDepth++
                        titleStart = collector.blocks.size
                    }
                    "empty-line" -> if (bodyDepth > 0 && !skippedBody) collector.breakBlock()
                    in PARAGRAPHS -> if (bodyDepth > 0 && !skippedBody) {
                        if (event.name == "subtitle") {
                            collector.startHeading()
                            subtitle = true
                        } else {
                            collector.breakBlock()
                        }
                        event["id"]?.let(collector::anchor)
                    }
                }
                is MarkupEvent.End -> when (event.name) {
                    "title-info" -> inTitleInfo = false
                    "author" -> inAuthor = false
                    "book-title", "first-name", "middle-name", "last-name" -> if (capture == event.name) {
                        val value = captured.toString().trim()
                        if (event.name == "book-title") title = value.ifEmpty { null }
                        else if (inAuthor && value.isNotEmpty()) author.append(if (author.isEmpty()) value else " $value")
                        capture = null
                    }
                    "body" -> {
                        collector.breakBlock()
                        bodyDepth--
                        skippedBody = false
                    }
                    "section" -> if (bodyDepth > 0 && !skippedBody) {
                        collector.breakBlock()
                        sectionDepth--
                    }
                    "title" -> if (titleDepth > 0) {
                        collector.endHeading()
                        titleDepth--
                        val heading = collector.blocks.subList(titleStart, collector.blocks.size).joinToString(" — ") { it.text }
                        if (heading.isNotEmpty() && sectionDepth > 0) toc += TocEntry(heading, titleStart, sectionDepth - 1)
                    }
                    in PARAGRAPHS -> if (bodyDepth > 0 && !skippedBody) {
                        if (event.name == "subtitle" && subtitle) {
                            collector.endHeading()
                            subtitle = false
                        } else {
                            collector.breakBlock()
                        }
                    }
                }
                is MarkupEvent.Text -> when {
                    capture != null -> captured.append(event.text)
                    bodyDepth > 0 && !skippedBody -> collector.append(event.text)
                }
            }
        }
        return ImportedBook(title, author.toString().ifEmpty { null }, collector.finish(), toc)
    }

    fun cover(bytes: ByteArray): ByteArray? = runCatching {
        val xml = TextDecoding.decodeMarkup(unzipped(bytes))
        var coverId: String? = null
        var inCoverPage = false
        var inBinary: String? = null
        val data = StringBuilder()
        var result: ByteArray? = null
        Markup.scan(xml) { event ->
            when (event) {
                is MarkupEvent.Start -> when (event.name) {
                    "coverpage" -> inCoverPage = true
                    "image" -> if (inCoverPage && coverId == null) {
                        coverId = event.attributes.entries.firstOrNull { it.key.endsWith("href") }?.value?.removePrefix("#")
                    }
                    "binary" -> if (result == null && event["id"] == coverId) {
                        inBinary = event["id"]
                        data.setLength(0)
                    }
                }
                is MarkupEvent.End -> when (event.name) {
                    "coverpage" -> inCoverPage = false
                    "binary" -> if (inBinary != null) {
                        result = Base64.getMimeDecoder().decode(data.toString())
                        inBinary = null
                    }
                }
                is MarkupEvent.Text -> if (inBinary != null) data.append(event.text)
            }
        }
        result
    }.getOrNull()

    // A .fb2.zip holds the book as its only (or first) .fb2 entry.
    private fun unzipped(bytes: ByteArray): ByteArray {
        if (!ZipArchive.isZip(bytes)) return bytes
        val zip = ZipArchive(bytes)
        val name = zip.names.firstOrNull { it.endsWith(".fb2", ignoreCase = true) } ?: zip.names.firstOrNull()
        return name?.let(zip::read) ?: throw UnsupportedBookException("Este arquivo FB2 compactado está vazio.")
    }
}
