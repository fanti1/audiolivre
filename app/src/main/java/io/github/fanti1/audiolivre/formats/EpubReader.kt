package io.github.fanti1.audiolivre.formats

import java.net.URLDecoder

// EPUB 2 and 3: a ZIP whose package document (OPF) lists the content files in reading order (the spine) and
// points at a table of contents — the EPUB 3 nav document or the older NCX.
internal object EpubReader {

    private class Item(val id: String, val path: String, val mediaType: String, val properties: String)

    private class Package(
        val title: String?,
        val author: String?,
        val items: Map<String, Item>,
        val spine: List<Item>,
        val ncx: Item?,
        val coverId: String?,
    ) {
        val nav: Item? get() = items.values.firstOrNull { "nav" in it.properties.split(' ') }
    }

    fun read(bytes: ByteArray, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): ImportedBook {
        val zip = ZipArchive(bytes)
        val pkg = readPackage(zip)
        rejectDrm(zip)
        val collector = BlockCollector()
        val fileStarts = HashMap<String, Int>()
        // The nav document is the table of contents; read aloud it would be a list of chapter names.
        val content = pkg.spine.filter { it != pkg.nav }
        content.forEachIndexed { i, item ->
            onProgress(i, content.size)
            val html = zip.readText(item.path) ?: return@forEachIndexed
            collector.breakBlock()
            fileStarts[item.path] = collector.blocks.size
            HtmlText.collect(html, collector, anchorPrefix = "${item.path}#")
        }
        val blocks = collector.finish()
        val resolve = { href: String ->
            val path = href.substringBefore('#')
            val fragment = href.substringAfter('#', "")
            (if (fragment.isNotEmpty()) collector.anchors["$path#$fragment"] else null) ?: fileStarts[path]
        }
        val toc = (pkg.nav?.let { readNav(zip, it, resolve) }.orEmpty())
            .ifEmpty { pkg.ncx?.let { readNcx(zip, it, resolve) }.orEmpty() }
        return ImportedBook(pkg.title, pkg.author, blocks, toc)
    }

    fun cover(bytes: ByteArray): ByteArray? = runCatching {
        val zip = ZipArchive(bytes)
        val pkg = readPackage(zip)
        val images = pkg.items.values.filter { it.mediaType.startsWith("image/") }
        val item = images.firstOrNull { "cover-image" in it.properties.split(' ') }
            ?: pkg.coverId?.let { pkg.items[it] }?.takeIf { it.mediaType.startsWith("image/") }
            ?: images.firstOrNull { "cover" in it.id.lowercase() || "cover" in it.path.lowercase() }
        item?.let { zip.read(it.path) }
    }.getOrNull()

    private fun readPackage(zip: ZipArchive): Package {
        val container = zip.readText("META-INF/container.xml")
        var opfPath: String? = null
        if (container != null) {
            Markup.scan(container) { event ->
                if (event is MarkupEvent.Start && event.name == "rootfile" && opfPath == null) opfPath = event["full-path"]
            }
        }
        val path = opfPath ?: zip.names.firstOrNull { it.endsWith(".opf", ignoreCase = true) }
            ?: throw UnsupportedBookException("Este EPUB está incompleto: falta o índice do livro.")
        val opf = zip.readText(path) ?: throw UnsupportedBookException("Este EPUB está corrompido.")
        val base = path.substringBeforeLast('/', "")

        var title: String? = null
        var author: String? = null
        var coverId: String? = null
        val items = LinkedHashMap<String, Item>()
        val spineIds = mutableListOf<String>()
        var tocId: String? = null
        var capture: String? = null
        val captured = StringBuilder()
        Markup.scan(opf) { event ->
            when (event) {
                is MarkupEvent.Start -> when (event.name) {
                    "dc:title", "dc:creator" -> if (!event.selfClosing) {
                        capture = event.name
                        captured.setLength(0)
                    }
                    "meta" -> if (event["name"] == "cover") coverId = event["content"]
                    "item" -> {
                        val id = event["id"] ?: return@scan
                        val href = event["href"] ?: return@scan
                        items[id] = Item(id, resolvePath(base, href), event["media-type"].orEmpty(), event["properties"].orEmpty())
                    }
                    "spine" -> tocId = event["toc"]
                    "itemref" -> if (event["linear"] != "no") event["idref"]?.let { spineIds += it }
                }
                is MarkupEvent.End -> if (event.name == capture) {
                    val value = captured.toString().replace(Regex("""\s+"""), " ").trim()
                    if (capture == "dc:title" && title == null && value.isNotEmpty()) title = value
                    if (capture == "dc:creator" && author == null && value.isNotEmpty()) author = value
                    capture = null
                }
                is MarkupEvent.Text -> if (capture != null) captured.append(event.text)
            }
        }
        val spine = spineIds.mapNotNull { items[it] }
            .filter { "html" in it.mediaType || it.path.endsWith("htm") || it.path.endsWith("html") }
        if (spine.isEmpty()) throw UnsupportedBookException("Este EPUB não tem texto para ler.")
        val ncx = tocId?.let { items[it] } ?: items.values.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }
        return Package(title, author, items, spine, ncx, coverId)
    }

    // Font obfuscation also lists files in encryption.xml; only encrypted text means the book is locked.
    private fun rejectDrm(zip: ZipArchive) {
        val encryption = zip.readText("META-INF/encryption.xml") ?: return
        var locked = false
        Markup.scan(encryption) { event ->
            if (event is MarkupEvent.Start && event.name.substringAfter(':') == "cipherreference") {
                val uri = event["uri"].orEmpty().lowercase()
                if (uri.endsWith(".xhtml") || uri.endsWith(".html") || uri.endsWith(".htm")) locked = true
            }
        }
        if (locked) throw UnsupportedBookException("Este livro tem DRM (proteção contra cópia) e não pode ser aberto.")
    }

    private fun readNav(zip: ZipArchive, nav: Item, resolve: (String) -> Int?): List<TocEntry> {
        val html = zip.readText(nav.path) ?: return emptyList()
        val base = nav.path.substringBeforeLast('/', "")
        val entries = mutableListOf<TocEntry>()
        var navDepth = 0
        var inToc = false
        var tocFound = false
        var listDepth = 0
        var href: String? = null
        val label = StringBuilder()
        Markup.scan(html) { event ->
            when (event) {
                is MarkupEvent.Start -> when (event.name) {
                    "nav" -> {
                        navDepth++
                        val type = event["epub:type"].orEmpty() + " " + event["role"].orEmpty()
                        if (!tocFound && ("toc" in type || navDepth == 1 && type.isBlank())) {
                            inToc = true
                            tocFound = "toc" in type
                        }
                    }
                    "ol", "ul" -> if (inToc) listDepth++
                    "a" -> if (inToc) {
                        href = event["href"]
                        label.setLength(0)
                    }
                }
                is MarkupEvent.End -> when (event.name) {
                    "nav" -> {
                        if (inToc && navDepth > 0) inToc = false
                        navDepth--
                    }
                    "ol", "ul" -> if (inToc) listDepth--
                    "a" -> {
                        val target = href
                        if (inToc && target != null) {
                            val title = label.toString().replace(Regex("""\s+"""), " ").trim()
                            val block = resolve(resolvePath(base, target))
                            if (title.isNotEmpty() && block != null) entries += TocEntry(title, block, (listDepth - 1).coerceAtLeast(0))
                        }
                        href = null
                    }
                }
                is MarkupEvent.Text -> if (href != null) label.append(event.text)
            }
        }
        return entries
    }

    private fun readNcx(zip: ZipArchive, ncx: Item, resolve: (String) -> Int?): List<TocEntry> {
        val xml = zip.readText(ncx.path) ?: return emptyList()
        val base = ncx.path.substringBeforeLast('/', "")
        val entries = mutableListOf<TocEntry>()
        var depth = 0
        var inLabel = false
        val label = StringBuilder()
        var pendingTitle: String? = null
        Markup.scan(xml) { event ->
            when (event) {
                is MarkupEvent.Start -> when (event.name) {
                    "navpoint" -> depth++
                    "navlabel" -> {
                        inLabel = true
                        label.setLength(0)
                    }
                    "content" -> {
                        val src = event["src"]
                        val title = pendingTitle
                        if (src != null && !title.isNullOrEmpty()) {
                            resolve(resolvePath(base, src))?.let { entries += TocEntry(title, it, (depth - 1).coerceAtLeast(0)) }
                        }
                        pendingTitle = null
                    }
                }
                is MarkupEvent.End -> when (event.name) {
                    "navpoint" -> depth--
                    "navlabel" -> {
                        inLabel = false
                        pendingTitle = label.toString().replace(Regex("""\s+"""), " ").trim()
                    }
                }
                is MarkupEvent.Text -> if (inLabel) label.append(event.text)
            }
        }
        return entries
    }

    // hrefs are relative to the file that contains them and URL-encoded ("Cap%C3%ADtulo%201.xhtml").
    internal fun resolvePath(base: String, href: String): String {
        val fragment = href.substringAfter('#', "")
        val raw = href.substringBefore('#')
        val decoded = runCatching { URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8") }.getOrDefault(raw)
        val parts = ArrayList<String>()
        if (!decoded.startsWith('/')) base.split('/').filter { it.isNotEmpty() }.forEach { parts += it }
        for (segment in decoded.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts += segment
            }
        }
        val path = parts.joinToString("/")
        return if (fragment.isEmpty()) path else "$path#$fragment"
    }
}
