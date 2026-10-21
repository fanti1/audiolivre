package io.github.fanti1.audiolivre.formats

// Word (.docx): word/document.xml holds paragraphs (<w:p>) made of runs whose text is in <w:t>. Headings are
// paragraphs styled "Heading 1…" — "Título 1…" in a Portuguese Word, whose style ids drop the accent ("Ttulo1").
internal object DocxReader {

    private val HEADING_STYLE = Regex("""^(heading|ttulo|t[ií]tulo|title|subtitle|subttulo)\s*\d*$""", RegexOption.IGNORE_CASE)

    fun read(bytes: ByteArray): ImportedBook {
        val zip = ZipArchive(bytes)
        val document = zip.readText("word/document.xml")
            ?: throw UnsupportedBookException("Este arquivo do Word está incompleto ou corrompido.")
        val collector = BlockCollector()
        var inText = false
        var heading = false
        var inParagraphProperties = false
        Markup.scan(document) { event ->
            when (event) {
                is MarkupEvent.Start -> when (event.name) {
                    "w:p" -> {
                        collector.breakBlock()
                        heading = false
                    }
                    "w:ppr" -> inParagraphProperties = !event.selfClosing
                    "w:pstyle" -> if (inParagraphProperties && HEADING_STYLE.matches(event["w:val"].orEmpty())) heading = true
                    "w:outlinelvl" -> if (inParagraphProperties && (event["w:val"]?.toIntOrNull() ?: 9) < 9) heading = true
                    "w:t" -> inText = !event.selfClosing
                    "w:tab" -> if (!inParagraphProperties) collector.append(" ")
                    "w:br", "w:cr" -> if (event["w:type"] == null) collector.append(" ")
                    "w:bookmarkstart" -> event["w:name"]?.let(collector::anchor)
                }
                is MarkupEvent.End -> when (event.name) {
                    "w:t" -> inText = false
                    "w:ppr" -> inParagraphProperties = false
                    "w:p" -> collector.breakBlock(asHeading = heading)
                }
                is MarkupEvent.Text -> if (inText) collector.append(event.text)
            }
        }
        val (title, author) = coreProperties(zip.readText("docProps/core.xml"))
        return ImportedBook(title, author, collector.finish())
    }

    fun cover(bytes: ByteArray): ByteArray? = runCatching {
        val zip = ZipArchive(bytes)
        zip.names.firstOrNull { it.startsWith("docProps/thumbnail.", ignoreCase = true) && !it.endsWith(".emf") && !it.endsWith(".wmf") }
            ?.let(zip::read)
    }.getOrNull()
}

// OpenDocument Text (.odt, LibreOffice): content.xml with <text:h> headings and <text:p> paragraphs.
internal object OdtReader {

    private val SKIPPED = setOf("text:note", "office:annotation", "text:tracked-changes", "svg:title", "svg:desc", "text:index-title")

    fun read(bytes: ByteArray): ImportedBook {
        val zip = ZipArchive(bytes)
        val content = zip.readText("content.xml")
            ?: throw UnsupportedBookException("Este arquivo do LibreOffice está incompleto ou corrompido.")
        val collector = BlockCollector()
        var skipping: String? = null
        var skipDepth = 0
        var inBody = false
        Markup.scan(content) { event ->
            when (event) {
                is MarkupEvent.Start -> when {
                    skipping != null -> if (event.name == skipping && !event.selfClosing) skipDepth++
                    event.name == "office:text" -> inBody = true
                    !inBody -> Unit
                    event.name in SKIPPED && !event.selfClosing -> {
                        skipping = event.name
                        skipDepth = 1
                    }
                    event.name == "text:h" -> {
                        collector.startHeading()
                        if (event.selfClosing) collector.endHeading()
                    }
                    event.name == "text:p" -> collector.breakBlock()
                    event.name == "text:s" -> collector.append(" ".repeat((event["text:c"]?.toIntOrNull() ?: 1).coerceIn(1, 8)))
                    event.name == "text:tab" -> collector.append(" ")
                    event.name == "text:line-break" -> collector.append(" ")
                    event.name == "text:bookmark" || event.name == "text:bookmark-start" -> event["text:name"]?.let(collector::anchor)
                }
                is MarkupEvent.End -> when {
                    skipping != null -> if (event.name == skipping && --skipDepth == 0) skipping = null
                    event.name == "text:h" -> collector.endHeading()
                    event.name == "text:p" -> collector.breakBlock()
                    event.name == "office:text" -> inBody = false
                }
                is MarkupEvent.Text -> if (inBody && skipping == null) collector.append(event.text)
            }
        }
        val (title, author) = coreProperties(zip.readText("meta.xml"))
        return ImportedBook(title, author, collector.finish())
    }

    // LibreOffice always saves a picture of the first page, which makes a fair cover.
    fun cover(bytes: ByteArray): ByteArray? = runCatching { ZipArchive(bytes).read("Thumbnails/thumbnail.png") }.getOrNull()
}

// Dublin Core title and author, as both Word (docProps/core.xml) and LibreOffice (meta.xml) store them.
private fun coreProperties(xml: String?): Pair<String?, String?> {
    if (xml == null) return null to null
    var capture: String? = null
    val captured = StringBuilder()
    var title: String? = null
    var author: String? = null
    Markup.scan(xml) { event ->
        when (event) {
            is MarkupEvent.Start -> if (!event.selfClosing && event.name in setOf("dc:title", "dc:creator", "meta:initial-creator")) {
                capture = event.name
                captured.setLength(0)
            }
            is MarkupEvent.End -> if (event.name == capture) {
                val value = captured.toString().trim().takeIf { it.isNotEmpty() }
                when (capture) {
                    "dc:title" -> title = title ?: value
                    else -> author = author ?: value
                }
                capture = null
            }
            is MarkupEvent.Text -> if (capture != null) captured.append(event.text)
        }
    }
    return title to author
}
