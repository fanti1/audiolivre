package io.github.fanti1.audiolivre.formats

// Plain text (.txt) and Markdown (.md). Paragraphs are separated by blank lines; text files that put one
// paragraph per line, with no blank lines at all, are read line by line instead. A text file hard-wrapped at
// ~70 columns (Project Gutenberg's) has its lines joined back into paragraphs.
internal object PlainTextReader {

    private const val MAX_HEADING_LENGTH = 70
    private const val MAX_SUBTITLE_LENGTH = 60
    private val CHAPTER_NUMBER = Regex("""^([IVXLC]{1,7}|\d{1,3})\.?$""")
    private val BLANK_LINE = Regex("""\n[ \t]*\n""")
    private val TXT_DASH = Regex("""(?<!-)--(?!-)""")
    private val TXT_ITALIC = Regex("""(?<![\p{L}\p{N}_])_([^_\n]+)_(?![\p{L}\p{N}_])""")
    private val MD_HEADING = Regex("""^#{1,6}\s+(.*?)\s*#*$""")
    private val MD_RULE = Regex("""^([-*_])(\s*\1){2,}$""")
    private val MD_LIST = Regex("""^(\s*)([-*+]|\d+[.)])\s+""")
    private val MD_IMAGE = Regex("""!\[[^\]]*]\([^)]*\)""")
    private val MD_LINK = Regex("""\[([^\]]+)]\([^)]*\)""")
    private val MD_STRONG = Regex("""(\*\*|__)(.+?)\1""")
    private val MD_EMPHASIS = Regex("""(?<![\p{L}\p{N}])([*_])(\S(?:.*?\S)?)\1(?![\p{L}\p{N}])""")
    private val MD_CODE = Regex("""`([^`]*)`""")

    fun read(bytes: ByteArray, markdown: Boolean): ImportedBook {
        val text = TextDecoding.decode(bytes).replace("\r\n", "\n").replace('\r', '\n')
        val blocks = if (markdown) markdownBlocks(text) else textBlocks(text)
        val title = if (markdown) blocks.firstOrNull { it.isHeading }?.text else null
        return ImportedBook(title, null, blocks)
    }

    private fun textBlocks(text: String): List<TextBlock> {
        val paragraphs = if (BLANK_LINE.containsMatchIn(text)) text.split(BLANK_LINE) else text.split('\n')
        val blocks = mutableListOf<TextBlock>()
        for (raw in paragraphs) {
            val lines = raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) continue
            val joined = typewriterMarks(lines.joinToString(" "))
            // "I" on its own and then "Do título." is how many old books number and name a chapter.
            val afterNumber = blocks.lastOrNull()?.let { it.isHeading && CHAPTER_NUMBER.matches(it.text) } == true
            val subtitle = afterNumber && joined.length <= MAX_SUBTITLE_LENGTH && !joined.startsWith('—') && !joined.startsWith('-')
            blocks += TextBlock(joined, isHeading = lines.size <= 2 && (looksLikeHeading(joined) || subtitle))
        }
        return blocks
    }

    // Plain text written on a typewriter's terms, as Project Gutenberg's is: "--" for a dash and _underscores_
    // around italics. On the page they look like typos, and the dash is the one that marks dialogue.
    private fun typewriterMarks(text: String): String =
        TXT_ITALIC.replace(TXT_DASH.replace(text, "—"), "$1")

    // A short line in capitals, a chapter number, or a "Capítulo …" line, standing on its own and not ending a
    // sentence.
    private fun looksLikeHeading(line: String): Boolean {
        if (CHAPTER_NUMBER.matches(line)) return true
        if (line.length > MAX_HEADING_LENGTH || line.last() in ".,;:!?…»”\"") return false
        val letters = line.filter { it.isLetter() }
        if (letters.length >= 3 && letters.all { it.isUpperCase() }) return true
        return Regex("""^(cap[íi]tulo|parte|livro)\s+\S+""", RegexOption.IGNORE_CASE).containsMatchIn(line)
    }

    private fun markdownBlocks(text: String): List<TextBlock> {
        val blocks = mutableListOf<TextBlock>()
        val paragraph = StringBuilder()
        fun flush() {
            val value = inline(paragraph.toString()).replace(Regex("""\s+"""), " ").trim()
            if (value.isNotEmpty()) blocks += TextBlock(value)
            paragraph.setLength(0)
        }
        var fence = false
        for (rawLine in text.split('\n')) {
            val line = rawLine.trim()
            if (line.startsWith("```") || line.startsWith("~~~")) {
                flush()
                fence = !fence
                continue
            }
            if (fence) continue
            val heading = MD_HEADING.matchEntire(line)
            when {
                line.isEmpty() -> flush()
                heading != null -> {
                    flush()
                    inline(heading.groupValues[1]).trim().takeIf { it.isNotEmpty() }?.let { blocks += TextBlock(it, isHeading = true) }
                }
                MD_RULE.matches(line) -> {
                    flush()
                    if (blocks.lastOrNull()?.text?.any { it.isLetterOrDigit() } == true) blocks += TextBlock(BlockCollector.SCENE_BREAK)
                }
                MD_LIST.containsMatchIn(rawLine) -> {
                    flush()
                    paragraph.append(rawLine.replaceFirst(MD_LIST, ""))
                }
                else -> {
                    if (paragraph.isNotEmpty()) paragraph.append(' ')
                    paragraph.append(line.removePrefix(">").trimStart('>', ' '))
                }
            }
        }
        flush()
        return blocks
    }

    private fun inline(text: String): String {
        var t = MD_IMAGE.replace(text, "")
        t = MD_LINK.replace(t, "$1")
        t = MD_CODE.replace(t, "$1")
        t = MD_STRONG.replace(t, "$2")
        t = MD_EMPHASIS.replace(t, "$2")
        return t.replace("\\", "")
    }
}
