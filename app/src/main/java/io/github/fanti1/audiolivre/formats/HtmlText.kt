package io.github.fanti1.audiolivre.formats

// Gathers text into blocks. Every format walks its own markup and tells this where paragraphs break and which
// ones are headings; anchors (element ids) are kept so a table of contents can point into the text.
internal class BlockCollector {
    val blocks = mutableListOf<TextBlock>()
    val anchors = HashMap<String, Int>()
    private val pendingAnchors = mutableListOf<String>()
    private val text = StringBuilder()
    private var headingDepth = 0

    fun append(value: String) {
        text.append(value)
    }

    fun anchor(id: String) {
        pendingAnchors += id
    }

    fun breakBlock(asHeading: Boolean = false) {
        val value = WHITESPACE.replace(text, " ").trim()
        text.setLength(0)
        if (value.isEmpty()) return
        claimAnchors()
        blocks += TextBlock(value, isHeading = asHeading || headingDepth > 0)
    }

    // Whether the text gathered so far ends a sentence, which a heading usually doesn't.
    fun pendingEndsSentence(): Boolean = text.trimEnd().lastOrNull()?.let { it in ".!?…:;,»”\"" } ?: false

    fun startHeading() {
        breakBlock()
        headingDepth++
    }

    fun endHeading() {
        breakBlock()
        if (headingDepth > 0) headingDepth--
    }

    // Printed as a centred "* * *": the reader sees a scene break and the voice leaves a silence there.
    fun sceneBreak() {
        breakBlock()
        val last = blocks.lastOrNull() ?: return
        if (last.text.any { it.isLetterOrDigit() }) blocks += TextBlock(SCENE_BREAK)
    }

    // An anchor with no text after it (the end of a file) points just past the last block.
    fun finish(): List<TextBlock> {
        breakBlock()
        claimAnchors()
        return blocks
    }

    private fun claimAnchors() {
        for (id in pendingAnchors) anchors.putIfAbsent(id, blocks.size)
        pendingAnchors.clear()
    }

    companion object {
        const val SCENE_BREAK = "* * *"
        private val WHITESPACE = Regex("""\s+""")
    }
}

// Turns HTML (from EPUBs, MOBI files or web pages) into blocks: block elements break paragraphs, h1–h6 are
// headings, and what a listener shouldn't hear — scripts, the <head>, footnote markers and the notes
// themselves — is left out.
internal object HtmlText {

    private val BLOCKS = setOf(
        "address", "article", "aside", "blockquote", "body", "caption", "center", "dd", "div", "dl", "dt",
        "figcaption", "figure", "footer", "form", "header", "li", "main", "nav", "ol", "p", "pre", "section",
        "table", "tbody", "td", "tfoot", "th", "thead", "tr", "ul", "br", "mbp:pagebreak",
    )
    private val HEADINGS = setOf("h1", "h2", "h3", "h4", "h5", "h6")
    private val BOLD = setOf("b", "strong")
    private const val MAX_BOLD_HEADING = 80
    private val SKIPPED = setOf("head", "script", "style", "svg", "math", "noscript", "template", "rt", "rp")
    private val NOTE_TYPES = Regex("""\b(noteref|footnote|endnote|endnotes|rearnote|rearnotes|footnotes)\b""")
    private val NOTE_ROLES = setOf("doc-noteref", "doc-footnote", "doc-endnote", "doc-endnotes")

    // Returns the document's <title>, which loose HTML files use as their book title. Old Kindle (MOBI) files set
    // chapter titles as centred paragraphs in bold instead of <h1>–<h6>; boldHeadings recognises those.
    fun collect(html: String, collector: BlockCollector, anchorPrefix: String = "", boldHeadings: Boolean = false): String? {
        var skipping: String? = null
        var skipDepth = 0
        var title: StringBuilder? = null
        var inTitle = false
        var boldDepth = 0
        var boldLetters = 0
        var plainLetters = 0
        var centered = false
        fun breakBlock() {
            val allBold = boldLetters in 1..MAX_BOLD_HEADING && plainLetters == 0
            collector.breakBlock(asHeading = boldHeadings && allBold && (centered || !collector.pendingEndsSentence()))
            boldLetters = 0
            plainLetters = 0
        }
        Markup.scan(html) { event ->
            when (event) {
                is MarkupEvent.Start -> {
                    if (event.name == "title") {
                        inTitle = !event.selfClosing
                        if (title == null) title = StringBuilder()
                    }
                    when {
                        skipping != null -> if (event.name == skipping && !event.selfClosing) skipDepth++
                        !event.selfClosing && (event.name in SKIPPED || isNote(event)) -> {
                            skipping = event.name
                            skipDepth = 1
                        }
                        else -> {
                            if (event.name in HEADINGS) {
                                breakBlock()
                                collector.startHeading()
                            } else if (event.name == "hr") {
                                breakBlock()
                                collector.sceneBreak()
                            } else if (event.name in BLOCKS) {
                                breakBlock()
                                centered = event.name == "center" || event["align"] == "center" ||
                                    event["style"]?.replace(" ", "")?.contains("text-align:center") == true
                            }
                            if (event.name in BOLD && !event.selfClosing) boldDepth++
                            (event["id"] ?: event["name"]?.takeIf { event.name == "a" })
                                ?.let { collector.anchor(anchorPrefix + it) }
                        }
                    }
                }
                is MarkupEvent.End -> {
                    if (event.name == "title") inTitle = false
                    when {
                        skipping != null -> if (event.name == skipping && --skipDepth == 0) skipping = null
                        event.name in HEADINGS -> collector.endHeading()
                        event.name in BLOCKS -> breakBlock()
                        event.name in BOLD -> if (boldDepth > 0) boldDepth--
                    }
                }
                is MarkupEvent.Text -> when {
                    inTitle -> title?.append(event.text)
                    skipping == null -> {
                        collector.append(event.text)
                        val letters = event.text.count { it.isLetterOrDigit() }
                        if (boldDepth > 0) boldLetters += letters else plainLetters += letters
                    }
                }
            }
        }
        breakBlock()
        return title?.toString()?.replace(Regex("""\s+"""), " ")?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun read(bytes: ByteArray): ImportedBook {
        val collector = BlockCollector()
        val title = collect(TextDecoding.decodeMarkup(bytes), collector)
        return ImportedBook(title = title, author = null, blocks = collector.finish())
    }

    private fun isNote(event: MarkupEvent.Start): Boolean {
        val type = event["epub:type"]
        if (type != null && NOTE_TYPES.containsMatchIn(type)) return true
        return event["role"] in NOTE_ROLES
    }
}
