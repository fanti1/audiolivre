package io.github.fanti1.audiolivre.formats

enum class BookFormat(val label: String) {
    PDF("PDF"),
    EPUB("EPUB"),
    MOBI("Kindle"),
    FB2("FB2"),
    DOCX("Word"),
    ODT("ODT"),
    RTF("RTF"),
    HTML("HTML"),
    TXT("TXT"),
    MARKDOWN("Markdown");

    companion object {
        // What the file picker offers. Kindle and FB2 files rarely have a registered type and arrive as
        // octet-stream, so that is included and the content decides.
        val MIME_TYPES = arrayOf(
            "application/pdf",
            "application/epub+zip",
            "application/x-mobipocket-ebook",
            "application/vnd.amazon.ebook",
            "application/vnd.amazon.mobi8-ebook",
            "application/x-fictionbook+xml",
            "application/x-fictionbook",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.oasis.opendocument.text",
            "application/rtf",
            "text/rtf",
            "text/html",
            "application/xhtml+xml",
            "text/plain",
            "text/markdown",
            "text/x-markdown",
            "application/zip",
            "application/octet-stream",
        )

        private val BY_EXTENSION = mapOf(
            "pdf" to PDF, "epub" to EPUB, "mobi" to MOBI, "azw" to MOBI, "azw3" to MOBI, "kf8" to MOBI, "prc" to MOBI,
            "fb2" to FB2, "docx" to DOCX, "odt" to ODT, "rtf" to RTF, "html" to HTML, "htm" to HTML, "xhtml" to HTML,
            "txt" to TXT, "text" to TXT, "md" to MARKDOWN, "markdown" to MARKDOWN,
        )
        private val KNOWN_SUFFIX = Regex(
            """\.(fb2\.zip|${BY_EXTENSION.keys.joinToString("|")})$""",
            RegexOption.IGNORE_CASE,
        )

        // The file name without its extension, as a stand-in title until the book's own is known.
        fun stripExtension(fileName: String): String = fileName.replace(KNOWN_SUFFIX, "")

        fun isPdf(head: ByteArray): Boolean {
            val limit = minOf(head.size, 1024) - 4
            for (i in 0 until limit) {
                if (head[i] == '%'.code.toByte() && head[i + 1] == 'P'.code.toByte() &&
                    head[i + 2] == 'D'.code.toByte() && head[i + 3] == 'F'.code.toByte()
                ) return true
            }
            return false
        }

        // The content decides first: files get renamed, and an ".epub" that is really a PDF should still open.
        fun detect(fileName: String?, bytes: ByteArray): BookFormat {
            val name = fileName.orEmpty().lowercase()
            val byName = BY_EXTENSION[name.substringAfterLast('.', "")]
            val head = String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1)
            return when {
                isPdf(bytes) -> PDF
                MobiReader.isMobi(bytes) -> MOBI
                ZipArchive.isZip(bytes) -> zipFormat(bytes, name)
                head.startsWith("{\\rtf") -> RTF
                bytes.size >= 4 && (bytes[0].toInt() and 0xFF) == 0xD0 && (bytes[1].toInt() and 0xFF) == 0xCF ->
                    throw UnsupportedBookException("Arquivos .doc antigos do Word não são suportados. Abra no Word ou no LibreOffice e salve como .docx.")
                head.contains("<FictionBook") -> FB2
                byName == MARKDOWN || byName == TXT -> byName
                Regex("""<(!doctype\s+html|html[\s>])""", RegexOption.IGNORE_CASE).containsMatchIn(head) -> HTML
                byName != null && byName != PDF && byName != EPUB && byName != DOCX && byName != ODT && byName != MOBI -> byName
                looksLikeText(bytes) -> TXT
                else -> throw UnsupportedBookException("O app não reconhece este tipo de arquivo.")
            }
        }

        private fun zipFormat(bytes: ByteArray, name: String): BookFormat {
            val zip = ZipArchive(bytes)
            val mimetype = zip.read("mimetype")?.let { String(it, Charsets.US_ASCII).trim() }
            return when {
                mimetype == "application/epub+zip" -> EPUB
                mimetype == "application/vnd.oasis.opendocument.text" -> ODT
                zip.contains("word/document.xml") -> DOCX
                zip.contains("META-INF/container.xml") -> EPUB
                zip.names.any { it.endsWith(".fb2", ignoreCase = true) } -> FB2
                mimetype?.startsWith("application/vnd.oasis.opendocument") == true ->
                    throw UnsupportedBookException("Planilhas e apresentações não são livros: o app lê documentos de texto.")
                name.endsWith(".cbz") || zip.names.all { it.substringAfterLast('.').lowercase() in IMAGE_EXTENSIONS || it.endsWith('/') } ->
                    throw UnsupportedBookException("Este arquivo só tem imagens (como um gibi): não há texto para ler.")
                else -> throw UnsupportedBookException("Este arquivo compactado não tem um livro que o app reconheça.")
            }
        }

        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")

        private fun looksLikeText(bytes: ByteArray): Boolean {
            val sample = bytes.size.coerceAtMost(8192)
            if (sample == 0) return false
            var control = 0
            for (i in 0 until sample) {
                val b = bytes[i].toInt() and 0xFF
                if (b == 0) return false
                if (b < 0x09 || b in 0x0E..0x1F) control++
            }
            return control * 50 < sample
        }
    }
}

// Every format except PDF, which keeps its own path through the page geometry.
internal object BookReaders {
    fun read(format: BookFormat, bytes: ByteArray, onProgress: (done: Int, total: Int) -> Unit): ImportedBook = when (format) {
        BookFormat.EPUB -> EpubReader.read(bytes, onProgress)
        BookFormat.MOBI -> MobiReader.read(bytes, onProgress)
        BookFormat.FB2 -> Fb2Reader.read(bytes)
        BookFormat.DOCX -> DocxReader.read(bytes)
        BookFormat.ODT -> OdtReader.read(bytes)
        BookFormat.RTF -> RtfReader.read(bytes)
        BookFormat.HTML -> HtmlText.read(bytes)
        BookFormat.TXT -> PlainTextReader.read(bytes, markdown = false)
        BookFormat.MARKDOWN -> PlainTextReader.read(bytes, markdown = true)
        BookFormat.PDF -> error("PDFs are read by PdfTextExtractor")
    }.withoutGutenbergFrame()

    fun cover(format: BookFormat, bytes: ByteArray): ByteArray? = when (format) {
        BookFormat.EPUB -> EpubReader.cover(bytes)
        BookFormat.MOBI -> MobiReader.cover(bytes)
        BookFormat.FB2 -> Fb2Reader.cover(bytes)
        BookFormat.DOCX -> DocxReader.cover(bytes)
        BookFormat.ODT -> OdtReader.cover(bytes)
        else -> null
    }
}
