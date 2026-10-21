package io.github.fanti1.audiolivre.formats

internal sealed interface MarkupEvent {
    // Names are lowercased and keep their namespace prefix ("w:p", "text:h", "epub:type").
    data class Start(val name: String, val attributes: Map<String, String>, val selfClosing: Boolean) : MarkupEvent {
        operator fun get(attribute: String): String? = attributes[attribute]
    }

    data class End(val name: String) : MarkupEvent

    data class Text(val text: String) : MarkupEvent
}

// A forgiving tag scanner for every markup format the app reads: XHTML from EPUBs, HTML from the web and
// from MOBI files, and the XML inside FB2, DOCX and ODT. Real books are full of markup a strict XML parser
// rejects (a stray "&", an unclosed <p>, "<" in the text), and one bad tag must not cost the whole book.
internal object Markup {

    private val RAW_TEXT = setOf("script", "style")

    fun scan(source: String, onEvent: (MarkupEvent) -> Unit) {
        val text = StringBuilder()
        fun flushText() {
            if (text.isNotEmpty()) {
                onEvent(MarkupEvent.Text(decodeEntities(text.toString())))
                text.setLength(0)
            }
        }

        var i = 0
        val n = source.length
        while (i < n) {
            val lt = source.indexOf('<', i)
            if (lt == -1) {
                text.append(source, i, n)
                break
            }
            text.append(source, i, lt)
            i = lt
            val next = source.getOrNull(i + 1)
            when {
                source.startsWith("<!--", i) -> i = skipPast(source, "-->", i + 4)
                source.startsWith("<![CDATA[", i) -> {
                    val end = source.indexOf("]]>", i + 9).let { if (it == -1) n else it }
                    flushText()
                    onEvent(MarkupEvent.Text(source.substring(i + 9, end)))
                    i = minOf(n, end + 3)
                }
                next == '!' || next == '?' -> i = skipPast(source, ">", i + 2)
                next == null || !(next.isLetter() || next == '/' || next == '_') -> {
                    text.append('<')
                    i++
                }
                else -> {
                    val end = tagEnd(source, i + 1)
                    if (end == -1) {
                        text.append(source, i, n)
                        break
                    }
                    flushText()
                    val event = parseTag(source.substring(i + 1, end))
                    i = end + 1
                    if (event != null) onEvent(event)
                    if (event is MarkupEvent.Start && !event.selfClosing && event.name in RAW_TEXT) {
                        val close = source.indexOf("</${event.name}", i, ignoreCase = true)
                        i = if (close == -1) n else skipPast(source, ">", close)
                        onEvent(MarkupEvent.End(event.name))
                    }
                }
            }
        }
        flushText()
    }

    private fun skipPast(source: String, marker: String, from: Int): Int {
        val at = source.indexOf(marker, from)
        return if (at == -1) source.length else at + marker.length
    }

    // The ">" that closes a tag, skipping any inside quoted attribute values.
    private fun tagEnd(source: String, from: Int): Int {
        var quote = 0.toChar()
        for (j in from until source.length) {
            val c = source[j]
            if (quote != 0.toChar()) {
                if (c == quote) quote = 0.toChar()
            } else if (c == '"' || c == '\'') {
                quote = c
            } else if (c == '>') {
                return j
            }
        }
        return -1
    }

    private fun parseTag(inner: String): MarkupEvent? {
        if (inner.startsWith('/')) {
            val name = inner.substring(1).trim().substringBefore(' ').lowercase()
            return if (name.isEmpty()) null else MarkupEvent.End(name)
        }
        val selfClosing = inner.endsWith('/')
        val body = if (selfClosing) inner.dropLast(1) else inner
        var p = 0
        while (p < body.length && !body[p].isWhitespace()) p++
        val name = body.substring(0, p).lowercase()
        if (name.isEmpty()) return null
        return MarkupEvent.Start(name, parseAttributes(body, p), selfClosing)
    }

    private fun parseAttributes(body: String, from: Int): Map<String, String> {
        val attributes = LinkedHashMap<String, String>()
        var p = from
        val n = body.length
        while (p < n) {
            while (p < n && body[p].isWhitespace()) p++
            val nameStart = p
            while (p < n && !body[p].isWhitespace() && body[p] != '=') p++
            if (p == nameStart) {
                p++
                continue
            }
            val name = body.substring(nameStart, p).lowercase()
            while (p < n && body[p].isWhitespace()) p++
            if (p < n && body[p] == '=') {
                p++
                while (p < n && body[p].isWhitespace()) p++
                val value: String
                if (p < n && (body[p] == '"' || body[p] == '\'')) {
                    val quote = body[p]
                    val close = body.indexOf(quote, p + 1).let { if (it == -1) n else it }
                    value = body.substring(p + 1, close)
                    p = close + 1
                } else {
                    val valueStart = p
                    while (p < n && !body[p].isWhitespace()) p++
                    value = body.substring(valueStart, p)
                }
                attributes[name] = decodeEntities(value)
            } else {
                attributes[name] = ""
            }
        }
        return attributes
    }

    fun decodeEntities(text: String): String {
        if ('&' !in text) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val semicolon = if (c == '&') text.indexOf(';', i + 1).takeIf { it != -1 && it - i <= 12 } else null
            val decoded = semicolon?.let { decodeEntity(text.substring(i + 1, it)) }
            if (semicolon != null && decoded != null) {
                out.append(decoded)
                i = semicolon + 1
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    private fun decodeEntity(name: String): String? {
        if (name.startsWith('#')) {
            val code = if (name.length > 1 && (name[1] == 'x' || name[1] == 'X')) {
                name.substring(2).toIntOrNull(16)
            } else {
                name.substring(1).toIntOrNull()
            }
            return code?.takeIf { Character.isValidCodePoint(it) && it != 0 }?.let { String(Character.toChars(it)) }
        }
        return ENTITIES[name]
    }

    private val ENTITIES: Map<String, String> = buildMap {
        put("amp", "&"); put("lt", "<"); put("gt", ">"); put("quot", "\""); put("apos", "'")
        // Latin-1, in code point order from U+00A0: every accented letter Portuguese uses is here.
        val latin1 = (
            "nbsp iexcl cent pound curren yen brvbar sect uml copy ordf laquo not shy reg macr deg plusmn sup2 sup3 " +
                "acute micro para middot cedil sup1 ordm raquo frac14 frac12 frac34 iquest Agrave Aacute Acirc Atilde " +
                "Auml Aring AElig Ccedil Egrave Eacute Ecirc Euml Igrave Iacute Icirc Iuml ETH Ntilde Ograve Oacute " +
                "Ocirc Otilde Ouml times Oslash Ugrave Uacute Ucirc Uuml Yacute THORN szlig agrave aacute acirc atilde " +
                "auml aring aelig ccedil egrave eacute ecirc euml igrave iacute icirc iuml eth ntilde ograve oacute " +
                "ocirc otilde ouml divide oslash ugrave uacute ucirc uuml yacute thorn yuml"
            ).split(' ')
        latin1.forEachIndexed { k, entity -> put(entity, (0xA0 + k).toChar().toString()) }
        val typographic = mapOf(
            "ndash" to 0x2013, "mdash" to 0x2014, "hellip" to 0x2026, "lsquo" to 0x2018, "rsquo" to 0x2019,
            "sbquo" to 0x201A, "ldquo" to 0x201C, "rdquo" to 0x201D, "bdquo" to 0x201E, "bull" to 0x2022,
            "prime" to 0x2032, "Prime" to 0x2033, "lsaquo" to 0x2039, "rsaquo" to 0x203A, "euro" to 0x20AC,
            "trade" to 0x2122, "dagger" to 0x2020, "Dagger" to 0x2021, "permil" to 0x2030, "thinsp" to 0x2009,
            "ensp" to 0x2002, "emsp" to 0x2003, "zwnj" to 0x200C, "zwj" to 0x200D, "OElig" to 0x152, "oelig" to 0x153,
            "Scaron" to 0x160, "scaron" to 0x161, "Yuml" to 0x178, "fnof" to 0x192, "circ" to 0x2C6, "tilde" to 0x2DC,
        )
        typographic.forEach { (entity, code) -> put(entity, code.toChar().toString()) }
    }
}
