package io.github.fanti1.audiolivre.formats

import io.github.fanti1.audiolivre.book.BookBuilder
import io.github.fanti1.audiolivre.book.SegmentKind
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class FormatReadersTest {

    private fun texts(book: ImportedBook) = book.blocks.map { it.text }
    private fun headings(book: ImportedBook) = book.blocks.filter { it.isHeading }.map { it.text }

    private fun zip(vararg entries: Pair<String, Any>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, content) in entries) {
                val bytes = if (content is ByteArray) content else content.toString().toByteArray()
                if (name == "mimetype") {
                    // EPUB stores its mimetype uncompressed, first.
                    val crc = java.util.zip.CRC32().apply { update(bytes) }
                    z.putNextEntry(ZipEntry(name).apply {
                        method = ZipEntry.STORED
                        size = bytes.size.toLong()
                        compressedSize = bytes.size.toLong()
                        this.crc = crc.value
                    })
                } else {
                    z.putNextEntry(ZipEntry(name))
                }
                z.write(bytes)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3, 4)

    // --- Markup and HTML ---

    @Test
    fun `entities and stray markup survive the scanner`() {
        val collector = BlockCollector()
        HtmlText.collect("<p>Ma&ccedil;&atilde; &amp; p&#227;o &mdash; 3 < 5 &bogus; &#x2026;</p>", collector)
        assertEquals(listOf("Maçã & pão — 3 < 5 &bogus; …"), collector.finish().map { it.text })
    }

    @Test
    fun `html blocks keep headings and drop scripts, the head and footnotes`() {
        val book = HtmlText.read(
            """
            <html><head><title>Dom Casmurro</title><style>p { color: red }</style></head>
            <body>
              <h2>Capítulo I<br/>Do título</h2>
              <p>Uma noite destas, vindo da cidade<a epub:type="noteref" href="#n1">1</a>, encontrei
                 no trem da Central um rapaz.</p>
              <script>var x = "<p>não</p>";</script>
              <p>— Continue, disse eu.</p>
              <hr/>
              <p>Depois.</p>
              <aside epub:type="footnote" id="n1"><p>Nota de rodapé.</p></aside>
            </body></html>
            """.trimIndent().toByteArray(),
        )
        assertEquals("Dom Casmurro", book.title)
        assertEquals(
            listOf(
                "Capítulo I", "Do título",
                "Uma noite destas, vindo da cidade, encontrei no trem da Central um rapaz.",
                "— Continue, disse eu.", "* * *", "Depois.",
            ),
            texts(book),
        )
        assertEquals(listOf("Capítulo I", "Do título"), headings(book))
    }

    @Test
    fun `text decoding falls back to windows-1252 and honours a declared charset`() {
        val latin = "Ação".toByteArray(charset("windows-1252"))
        assertEquals("Ação", TextDecoding.decode(latin))
        assertEquals("Ação", TextDecoding.decode("Ação".toByteArray()))
        val declared = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><p>Ação</p>".toByteArray(Charsets.ISO_8859_1)
        assertTrue(TextDecoding.decodeMarkup(declared).contains("Ação"))
    }

    // --- EPUB ---

    private fun epub3(): ByteArray = zip(
        "mimetype" to "application/epub+zip",
        "META-INF/container.xml" to """<?xml version="1.0"?><container><rootfiles>
            <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
        "OEBPS/content.opf" to """<?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>Memórias Póstumas</dc:title><dc:creator>Machado de Assis</dc:creator>
              </metadata>
              <manifest>
                <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                <item id="c1" href="Text/cap%201.xhtml" media-type="application/xhtml+xml"/>
                <item id="c2" href="Text/cap2.xhtml" media-type="application/xhtml+xml"/>
                <item id="img" href="Images/capa.jpg" media-type="image/jpeg" properties="cover-image"/>
              </manifest>
              <spine><itemref idref="nav"/><itemref idref="c1"/><itemref idref="c2"/></spine>
            </package>""",
        "OEBPS/nav.xhtml" to """<html><body><nav epub:type="toc"><ol>
              <li><a href="Text/cap%201.xhtml">Óbito do autor</a></li>
              <li><a href="Text/cap2.xhtml#c2b">O emplasto</a></li>
            </ol></nav><nav epub:type="landmarks"><ol><li><a href="Text/cap2.xhtml">Início</a></li></ol></nav></body></html>""",
        "OEBPS/Text/cap 1.xhtml" to """<html><body><h1>Capítulo I</h1><p>Algum tempo hesitei se devia abrir.</p></body></html>""",
        "OEBPS/Text/cap2.xhtml" to """<html><body><p>Fim do primeiro.</p><h1 id="c2b">Capítulo II</h1><p>Com efeito, um dia.</p></body></html>""",
        "OEBPS/Images/capa.jpg" to jpeg,
    )

    @Test
    fun `epub 3 reads the spine in order, its metadata and its nav table of contents`() {
        val book = EpubReader.read(epub3())
        assertEquals("Memórias Póstumas", book.title)
        assertEquals("Machado de Assis", book.author)
        assertEquals(
            listOf("Capítulo I", "Algum tempo hesitei se devia abrir.", "Fim do primeiro.", "Capítulo II", "Com efeito, um dia."),
            texts(book),
        )
        assertEquals(listOf(TocEntry("Óbito do autor", 0), TocEntry("O emplasto", 3)), book.toc)
        assertArrayEquals(jpeg, EpubReader.cover(epub3()))
    }

    @Test
    fun `epub 2 falls back to the ncx and to the meta cover`() {
        val bytes = zip(
            "mimetype" to "application/epub+zip",
            "META-INF/container.xml" to """<container><rootfiles><rootfile full-path="content.opf"/></rootfiles></container>""",
            "content.opf" to """<package><metadata><dc:title>Iracema</dc:title><meta name="cover" content="cv"/></metadata>
                <manifest><item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                <item id="t" href="text.html" media-type="application/xhtml+xml"/>
                <item id="cv" href="cover.jpeg" media-type="image/jpeg"/></manifest>
                <spine toc="ncx"><itemref idref="t"/></spine></package>""",
            "toc.ncx" to """<ncx><navMap><navPoint><navLabel><text>Prólogo</text></navLabel><content src="text.html#p"/>
                <navPoint><navLabel><text>Parte</text></navLabel><content src="text.html"/></navPoint></navPoint></navMap></ncx>""",
            "text.html" to """<html><body><p>Antes.</p><p id="p">Verdes mares bravios.</p></body></html>""",
            "cover.jpeg" to jpeg,
        )
        val book = EpubReader.read(bytes)
        assertEquals(listOf(TocEntry("Prólogo", 1, 0), TocEntry("Parte", 0, 1)), book.toc)
        assertArrayEquals(jpeg, EpubReader.cover(bytes))
        assertEquals(BookFormat.EPUB, BookFormat.detect("livro.bin", bytes))
    }

    @Test(expected = UnsupportedBookException::class)
    fun `an epub with encrypted text is refused as DRM`() {
        val bytes = zip(
            "mimetype" to "application/epub+zip",
            "META-INF/container.xml" to """<container><rootfiles><rootfile full-path="c.opf"/></rootfiles></container>""",
            "META-INF/encryption.xml" to """<encryption><enc:EncryptedData><enc:CipherData>
                <enc:CipherReference URI="text.xhtml"/></enc:CipherData></enc:EncryptedData></encryption>""",
            "c.opf" to """<package><manifest><item id="t" href="text.xhtml" media-type="application/xhtml+xml"/></manifest>
                <spine><itemref idref="t"/></spine></package>""",
            "text.xhtml" to "xx",
        )
        EpubReader.read(bytes)
    }

    // --- FB2 ---

    @Test
    fun `fb2 reads nested sections, titles as a table of contents, skips notes and finds the cover`() {
        val cover = Base64.getEncoder().encodeToString(jpeg)
        val xml = """<?xml version="1.0" encoding="utf-8"?>
            <FictionBook xmlns:l="http://www.w3.org/1999/xlink"><description><title-info>
              <author><first-name>José</first-name><last-name>de Alencar</last-name></author>
              <book-title>O Guarani</book-title><coverpage><image l:href="#capa.jpg"/></coverpage>
            </title-info></description>
            <body><section><title><p>Primeira parte</p></title>
              <section><title><p>Capítulo I</p><p>Cenário</p></title><p>De um dos cabeços da Serra dos Órgãos.</p>
              <empty-line/><p>Corre o Paquequer.</p></section></section></body>
            <body name="notes"><section><p>Nota que não se lê.</p></section></body>
            <binary id="capa.jpg" content-type="image/jpeg">$cover</binary></FictionBook>"""
        val book = Fb2Reader.read(xml.toByteArray())
        assertEquals("O Guarani", book.title)
        assertEquals("José de Alencar", book.author)
        assertEquals(
            listOf("Primeira parte", "Capítulo I", "Cenário", "De um dos cabeços da Serra dos Órgãos.", "Corre o Paquequer."),
            texts(book),
        )
        assertEquals(listOf("Primeira parte", "Capítulo I", "Cenário"), headings(book))
        assertEquals(listOf(TocEntry("Primeira parte", 0, 0), TocEntry("Capítulo I — Cenário", 1, 1)), book.toc)
        assertArrayEquals(jpeg, Fb2Reader.cover(xml.toByteArray()))
        assertEquals(BookFormat.FB2, BookFormat.detect("x.fb2", xml.toByteArray()))
        val zipped = zip("livro.fb2" to xml)
        assertEquals(BookFormat.FB2, BookFormat.detect("x.fb2.zip", zipped))
        assertEquals("O Guarani", Fb2Reader.read(zipped).title)
    }

    // --- Word and LibreOffice ---

    @Test
    fun `docx joins runs into paragraphs and recognises Portuguese heading styles`() {
        val document = """<w:document><w:body>
            <w:p><w:pPr><w:pStyle w:val="Ttulo1"/><w:tabs><w:tab w:val="left"/></w:tabs></w:pPr><w:r><w:t>Capítulo 1</w:t></w:r></w:p>
            <w:p><w:r><w:t xml:space="preserve">Era uma </w:t></w:r><w:r><w:rPr><w:b/></w:rPr><w:t>vez</w:t></w:r><w:r><w:tab/><w:t>um rei.</w:t></w:r>
              <w:del><w:r><w:delText>apagado</w:delText></w:r></w:del></w:p>
            <w:p/>
            <w:p><w:pPr><w:pStyle w:val="Normal"/></w:pPr><w:r><w:instrText>PAGE</w:instrText><w:t>Fim.</w:t></w:r></w:p>
            </w:body></w:document>"""
        val bytes = zip(
            "[Content_Types].xml" to "<Types/>",
            "word/document.xml" to document,
            "docProps/core.xml" to """<cp:coreProperties><dc:title>Meu Livro</dc:title><dc:creator>Ana</dc:creator></cp:coreProperties>""",
        )
        assertEquals(BookFormat.DOCX, BookFormat.detect("a.docx", bytes))
        val book = DocxReader.read(bytes)
        assertEquals(listOf("Capítulo 1", "Era uma vez um rei.", "Fim."), texts(book))
        assertEquals(listOf("Capítulo 1"), headings(book))
        assertEquals("Meu Livro", book.title)
        assertEquals("Ana", book.author)
    }

    @Test
    fun `odt reads headings, expands spaces and leaves notes out`() {
        val bytes = zip(
            "mimetype" to "application/vnd.oasis.opendocument.text",
            "content.xml" to """<office:document-content><office:body><office:text>
                <text:h text:outline-level="1">Parte Um</text:h>
                <text:p>Ela disse<text:s text:c="2"/>que<text:note><text:note-citation>1</text:note-citation>
                <text:note-body><text:p>nota</text:p></text:note-body></text:note> sim.</text:p>
                <text:list><text:list-item><text:p>Item da lista</text:p></text:list-item></text:list>
                </office:text></office:body></office:document-content>""",
            "meta.xml" to """<office:document-meta><office:meta><dc:title>Contos</dc:title></office:meta></office:document-meta>""",
            "Thumbnails/thumbnail.png" to jpeg,
        )
        assertEquals(BookFormat.ODT, BookFormat.detect("a.odt", bytes))
        val book = OdtReader.read(bytes)
        assertEquals(listOf("Parte Um", "Ela disse que sim.", "Item da lista"), texts(book))
        assertEquals(listOf("Parte Um"), headings(book))
        assertEquals("Contos", book.title)
        assertArrayEquals(jpeg, OdtReader.cover(bytes))
    }

    // --- RTF ---

    @Test
    fun `rtf decodes code page bytes and unicode, skips tables and reads headings and the title`() {
        val rtf = """{\rtf1\ansi\ansicpg1252\deff0{\fonttbl{\f0 Times New Roman;}}{\colortbl;\red0\green0\blue0;}
            {\info{\title O Corti\'e7o}{\author Alu\'edsio}}
            {\*\generator Writer}
            \pard\outlinelevel0 Cap\'edtulo I\par
            \pard Jo\'e3o Rom\u227?o foi, dos treze aos vinte e cinco anos,\line empregado.\par
            {\footnote Nota.}\pard Fim\emdash fim.\par}"""
        val book = RtfReader.read(rtf.toByteArray(Charsets.ISO_8859_1))
        assertEquals(listOf("Capítulo I", "João Romão foi, dos treze aos vinte e cinco anos, empregado.", "Fim—fim."), texts(book))
        assertEquals(listOf("Capítulo I"), headings(book))
        assertEquals("O Cortiço", book.title)
        assertEquals("Aluísio", book.author)
        assertEquals(BookFormat.RTF, BookFormat.detect("a.rtf", rtf.toByteArray()))
    }

    // --- Plain text and Markdown ---

    @Test
    fun `hard wrapped text is joined into paragraphs and capital lines become headings`() {
        val text = "CAPITULO PRIMEIRO\n\nUma noite destas, vindo da cidade para o Engenho Novo,\nencontrei no trem da Central.\n\n" +
            "Cumprimentou-me, sentou-se ao pé de mim.\n"
        val book = PlainTextReader.read(text.toByteArray(), markdown = false)
        assertEquals(
            listOf(
                "CAPITULO PRIMEIRO",
                "Uma noite destas, vindo da cidade para o Engenho Novo, encontrei no trem da Central.",
                "Cumprimentou-me, sentou-se ao pé de mim.",
            ),
            texts(book),
        )
        assertEquals(listOf("CAPITULO PRIMEIRO"), headings(book))
    }

    @Test
    fun `typewriter dashes and underscore italics in plain text become a dash and plain words`() {
        val text = "--Sim, disse ella.\n\nMorava na _Rua dos Ourives_, 7--9, com o meu_amigo.\n\n----\n"
        val book = PlainTextReader.read(text.toByteArray(), markdown = false)
        assertEquals(listOf("—Sim, disse ella.", "Morava na Rua dos Ourives, 7—9, com o meu_amigo.", "----"), texts(book))
    }

    @Test
    fun `text with one paragraph per line and no blank lines keeps each line`() {
        val book = PlainTextReader.read("Primeira linha.\nSegunda linha.\r\nTerceira.".toByteArray(), markdown = false)
        assertEquals(listOf("Primeira linha.", "Segunda linha.", "Terceira."), texts(book))
    }

    @Test
    fun `the project gutenberg header and license are left out`() {
        val text = "The Project Gutenberg eBook of Dom Casmurro\n\nThis ebook is for the use of anyone.\n\n" +
            "*** START OF THE PROJECT GUTENBERG EBOOK DOM CASMURRO ***\n\nDOM CASMURRO\n\nTexto do livro.\n\n" +
            "*** END OF THE PROJECT GUTENBERG EBOOK DOM CASMURRO ***\n\nSection 1. General Terms of Use."
        val book = BookReaders.read(BookFormat.TXT, text.toByteArray()) { _, _ -> }
        assertEquals(listOf("DOM CASMURRO", "Texto do livro."), texts(book))
    }

    @Test
    fun `markdown keeps headings and drops its markup`() {
        val md = "# O Alienista\n\nAs crônicas da vila de **Itaguaí** dizem que _em tempos remotos_ vivera ali\num " +
            "[médico](http://x), o Dr. Simão Bacamarte.\n\n![capa](capa.png)\n\n---\n\n## Capítulo II\n\n```\ncódigo\n```\n" +
            "- Um item\n"
        val book = PlainTextReader.read(md.toByteArray(), markdown = true)
        assertEquals(
            listOf(
                "O Alienista",
                "As crônicas da vila de Itaguaí dizem que em tempos remotos vivera ali um médico, o Dr. Simão Bacamarte.",
                "* * *", "Capítulo II", "Um item",
            ),
            texts(book),
        )
        assertEquals(listOf("O Alienista", "Capítulo II"), headings(book))
        assertEquals("O Alienista", book.title)
    }

    // --- Kindle ---

    @Test
    fun `palmdoc decompression handles literals, back references and space pairs`() {
        val compressed = byteArrayOf('a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte(), 0x80.toByte(), 0x18, 0xE1.toByte(),
            2, 0xC3.toByte(), 0xA7.toByte())
        assertEquals("abcabc aç", String(MobiReader.decompressPalmDoc(compressed), Charsets.UTF_8))
    }

    @Test
    fun `a drm free mobi is decompressed, trimmed and read as html`() {
        val html = "<html><body><h2>Capítulo I</h2><p>Era uma vez — disse ela.</p><mbp:pagebreak/><p>Fim.</p></body></html>"
        val bytes = mobi(html, title = "Iracema", encryption = 0)
        assertTrue(MobiReader.isMobi(bytes))
        assertEquals(BookFormat.MOBI, BookFormat.detect("iracema.azw3", bytes))
        val book = MobiReader.read(bytes)
        assertEquals("Iracema", book.title)
        assertEquals(listOf("Capítulo I", "Era uma vez — disse ela.", "Fim."), texts(book))
        assertEquals(listOf("Capítulo I"), headings(book))
    }

    @Test(expected = UnsupportedBookException::class)
    fun `a kindle book with drm is refused`() {
        MobiReader.read(mobi("<p>x</p>", title = "x", encryption = 2))
    }

    // A minimal Palm database: record 0 with the PalmDOC and MOBI headers and an EXTH title, then one text
    // record compressed as PalmDOC literals and ending in one multibyte trailing byte.
    private fun mobi(html: String, title: String, encryption: Int): ByteArray {
        val text = html.toByteArray(Charsets.UTF_8)
        val compressed = ByteArrayOutputStream()
        var i = 0
        while (i < text.size) {
            val b = text[i].toInt() and 0xFF
            if (b in 0x09..0x7F) {
                compressed.write(b)
                i++
            } else {
                val run = minOf(8, text.size - i)
                compressed.write(run)
                compressed.write(text, i, run)
                i += run
            }
        }
        compressed.write(0) // trailing multibyte entry: (0 & 3) + 1 = one byte
        val record1 = compressed.toByteArray()

        val titleBytes = title.toByteArray()
        val exth = ByteArrayOutputStream().apply {
            write("EXTH".toByteArray()); writeInt(12 + 8 + titleBytes.size); writeInt(1)
            writeInt(503); writeInt(8 + titleBytes.size); write(titleBytes)
        }.toByteArray()
        val mobiLength = 232
        val record0 = ByteArray(16 + mobiLength + exth.size + titleBytes.size)
        fun put16(p: Int, v: Int) { record0[p] = (v shr 8).toByte(); record0[p + 1] = v.toByte() }
        fun put32(p: Int, v: Long) { put16(p, (v shr 16).toInt()); put16(p + 2, v.toInt()) }
        put16(0, 2)
        put32(4, text.size.toLong())
        put16(8, 1)
        put16(10, 4096)
        put16(12, encryption)
        "MOBI".toByteArray().copyInto(record0, 16)
        put32(20, mobiLength.toLong())
        put32(24, 2)
        put32(28, 65001)
        put32(0x54, (16 + mobiLength + exth.size).toLong())
        put32(0x58, titleBytes.size.toLong())
        put32(0x6C, 0xFFFFFFFFL)
        put32(0x80, 0x40)
        put16(0xF2, 1)
        exth.copyInto(record0, 16 + mobiLength)
        titleBytes.copyInto(record0, 16 + mobiLength + exth.size)

        val out = ByteArrayOutputStream()
        val header = ByteArray(78)
        "book".toByteArray().copyInto(header, 0)
        "BOOKMOBI".toByteArray().copyInto(header, 60)
        header[77] = 2
        out.write(header)
        val first = 78 + 2 * 8 + 2
        out.writeInt(first); out.writeInt(0)
        out.writeInt(first + record0.size); out.writeInt(1)
        out.write(byteArrayOf(0, 0))
        out.write(record0)
        out.write(record1)
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.writeInt(v: Int) {
        write(v ushr 24); write(v ushr 16); write(v ushr 8); write(v)
    }

    // --- Detection and the book model ---

    @Test
    fun `detection trusts the content over the file name`() {
        assertEquals(BookFormat.PDF, BookFormat.detect("livro.epub", "%PDF-1.7\n...".toByteArray()))
        assertEquals(BookFormat.HTML, BookFormat.detect("pagina", "<!DOCTYPE html><html><p>x</p></html>".toByteArray()))
        assertEquals(BookFormat.TXT, BookFormat.detect("sem-extensao", "Só texto.".toByteArray()))
        assertEquals(BookFormat.MARKDOWN, BookFormat.detect("notas.md", "# Título".toByteArray()))
        assertEquals("Dom Casmurro", BookFormat.stripExtension("Dom Casmurro.fb2.zip"))
        assertEquals("Dom Casmurro", BookFormat.stripExtension("Dom Casmurro.azw3"))
    }

    @Test(expected = UnsupportedBookException::class)
    fun `old binary word documents are refused with an explanation`() {
        BookFormat.detect("a.doc", byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0, 0, 0, 0))
    }

    @Test
    fun `blocks become a book with chapters from the table of contents and dialogue segments`() {
        val book = EpubReader.read(epub3())
        val document = BookBuilder.fromBlocks(
            book.blocks + TextBlock("— Não vou — disse ela."),
            book.toc,
            book.title ?: "x",
        )
        assertEquals(listOf("Óbito do autor", "O emplasto"), document.chapters.map { it.title })
        assertEquals(listOf(0, 3), document.chapters.map { it.firstParagraph })
        assertTrue(document.paragraphs[0].isHeading)
        assertFalse(document.paragraphs[1].isHeading)
        assertTrue(document.sentences.any { it.kind == SegmentKind.SPEECH })
        assertNull(MobiReader.cover(mobi("<p>x</p>", "x", 0)))
    }
}
