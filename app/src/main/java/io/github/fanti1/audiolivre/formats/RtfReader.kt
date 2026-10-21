package io.github.fanti1.audiolivre.formats

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

// Rich Text Format: text interleaved with control words ("\par", "\'e7", "\u231?") inside nested {groups}.
// Only the body text is wanted; font tables, styles, pictures, headers and footnotes are groups to skip.
internal object RtfReader {

    private enum class Destination { TEXT, SKIP, TITLE, AUTHOR }

    private class State(var destination: Destination, var unicodeSkip: Int)

    private val SKIPPED_DESTINATIONS = setOf(
        "fonttbl", "colortbl", "stylesheet", "info", "pict", "object", "header", "headerl", "headerr", "headerf",
        "footer", "footerl", "footerr", "footerf", "footnote", "annotation", "fldinst", "datastore", "themedata",
        "colorschememapping", "latentstyles", "rsidtbl", "xmlnstbl", "listtable", "listoverridetable", "generator",
        "mmathpr", "listtext", "pntext", "pntxta", "pntxtb", "bkmkstart", "bkmkend", "shppict", "nonshppict", "xe", "tc",
    )
    private val PARAGRAPH_ENDS = setOf("par", "sect", "page", "cell", "row")
    private val SYMBOLS = mapOf(
        "emdash" to "—", "endash" to "–", "lquote" to "‘", "rquote" to "’", "ldblquote" to "“", "rdblquote" to "”",
        "bullet" to "•", "tab" to " ", "emspace" to " ", "enspace" to " ", "qmspace" to " ",
    )

    fun read(bytes: ByteArray): ImportedBook {
        // Read as Latin-1 so every byte maps to one char; real text bytes are decoded with the document's code page.
        val source = String(bytes, Charsets.ISO_8859_1)
        if (!source.startsWith("{\\rtf")) throw UnsupportedBookException("Este arquivo RTF está corrompido.")
        var codePage: Charset = Charset.forName("windows-1252")
        val collector = BlockCollector()
        val title = StringBuilder()
        val author = StringBuilder()
        val stack = ArrayDeque<State>()
        var state = State(Destination.TEXT, 1)
        var heading = false
        var skipChars = 0
        val pendingBytes = ByteArrayOutputStream()

        fun emit(text: String) {
            when (state.destination) {
                Destination.TEXT -> collector.append(text)
                Destination.TITLE -> title.append(text)
                Destination.AUTHOR -> author.append(text)
                Destination.SKIP -> Unit
            }
        }

        fun flushBytes() {
            if (pendingBytes.size() > 0) {
                emit(String(pendingBytes.toByteArray(), codePage))
                pendingBytes.reset()
            }
        }

        fun endParagraph() {
            flushBytes()
            if (state.destination == Destination.TEXT) collector.breakBlock(asHeading = heading)
        }

        var i = 0
        val n = source.length
        var groupStart = false
        while (i < n) {
            val c = source[i]
            when (c) {
                '{' -> {
                    flushBytes()
                    stack.addLast(state)
                    state = State(state.destination, state.unicodeSkip)
                    groupStart = true
                    i++
                    continue
                }
                '}' -> {
                    flushBytes()
                    state = stack.removeLastOrNull() ?: state
                    i++
                }
                '\\' -> {
                    val next = source.getOrNull(i + 1) ?: break
                    when {
                        next.isLetter() -> {
                            var j = i + 1
                            while (j < n && source[j].isLetter()) j++
                            val word = source.substring(i + 1, j)
                            var k = j
                            if (k < n && (source[k] == '-' || source[k].isDigit())) {
                                k++
                                while (k < n && source[k].isDigit()) k++
                            }
                            val parameter = source.substring(j, k).toIntOrNull()
                            if (k < n && source[k] == ' ') k++
                            i = k
                            // Not a hex byte: whatever was pending is complete. The fallback a "\u" leaves for old
                            // readers is plain text or hex bytes, never a control word.
                            flushBytes()
                            if (word != "u") skipChars = 0
                            when {
                                word == "u" && parameter != null -> {
                                    val code = if (parameter < 0) parameter + 65536 else parameter
                                    emit(code.toChar().toString())
                                    skipChars = state.unicodeSkip
                                }
                                word == "uc" -> state.unicodeSkip = parameter ?: 1
                                word == "ansicpg" && parameter != null ->
                                    codePage = runCatching { Charset.forName("windows-$parameter") }.getOrDefault(codePage)
                                word in PARAGRAPH_ENDS -> endParagraph()
                                word == "line" -> emit(" ")
                                word == "pard" -> heading = false
                                word == "outlinelevel" -> heading = (parameter ?: 9) < 9
                                word == "title" && state.destination == Destination.SKIP && groupStart -> state.destination = Destination.TITLE
                                word == "author" && state.destination == Destination.SKIP && groupStart -> state.destination = Destination.AUTHOR
                                word in SKIPPED_DESTINATIONS && groupStart -> state.destination = Destination.SKIP
                                word in SYMBOLS -> emit(SYMBOLS.getValue(word))
                            }
                        }
                        next == '\'' -> {
                            val hex = source.substring(i + 2, minOf(n, i + 4)).toIntOrNull(16)
                            i += 4
                            if (skipChars > 0) skipChars-- else if (hex != null) pendingBytes.write(hex)
                        }
                        next == '*' -> {
                            // "{\*\name ...}": a destination this reader may not know; unknown ones are skipped.
                            if (groupStart) state.destination = Destination.SKIP
                            i += 2
                            continue
                        }
                        next == '\n' || next == '\r' -> {
                            endParagraph()
                            i += 2
                        }
                        next == '~' -> {
                            flushBytes()
                            emit(" ")
                            i += 2
                        }
                        next == '_' -> {
                            flushBytes()
                            emit("-")
                            i += 2
                        }
                        next == '-' -> i += 2
                        else -> {
                            flushBytes()
                            if (skipChars > 0) skipChars-- else emit(next.toString())
                            i += 2
                        }
                    }
                }
                '\r', '\n' -> i++
                else -> {
                    flushBytes()
                    if (skipChars > 0) skipChars-- else emit(c.toString())
                    i++
                }
            }
            groupStart = false
        }
        endParagraph()
        return ImportedBook(
            title = title.toString().trim().ifEmpty { null },
            author = author.toString().trim().ifEmpty { null },
            blocks = collector.finish(),
        )
    }
}
