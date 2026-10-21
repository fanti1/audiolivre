package io.github.fanti1.audiolivre.book

// Puts back the large initial letter that opens a chapter in most novels.
//
// A drop cap is one glyph set three or four lines tall, so the PDF stores it as its own line whose baseline
// sits well below the line it belongs to. Sorted by position it lands in the middle of the paragraph: the
// chapter loses its first letter and gains a stray capital a line later — "arina chegou … M quando a rua".
//
// The lines beside the cap are also indented past it, which the paragraph assembler would read as the start
// of a new paragraph, so they are pulled back to the real margin once the letter is restored.
object DropCap {

    // The cap is set several lines tall; anything smaller than this is just a large word.
    private const val MIN_FONT_RATIO = 1.8f

    // How far from the cap's own baseline the lines it covers may sit, in multiples of the cap's size.
    // Which side they fall on depends on where the font puts the baseline of a glyph this tall, so the
    // distance is measured either way and the topmost line of the group wins.
    private const val SPAN_ABOVE = 1.2f

    fun merge(lines: List<PdfLine>, bodyFontSize: Float): List<PdfLine> {
        if (bodyFontSize <= 0f || lines.isEmpty()) return lines
        val caps = lines.indices.filter { isCap(lines[it], bodyFontSize) }
        if (caps.isEmpty()) return lines

        val result = lines.toMutableList()
        val dropped = HashSet<Int>()
        for (i in caps) {
            val cap = lines[i]
            val beside = lines.indices.filter { j ->
                j != i && lines[j].page == cap.page &&
                    lines[j].x >= cap.right - bodyFontSize &&
                    kotlin.math.abs(lines[j].y - cap.y) <= cap.fontSize * SPAN_ABOVE
            }
            // The letter belongs to the topmost line set beside it: that is where the paragraph starts.
            val first = beside.minByOrNull { lines[it].y } ?: continue
            dropped += i
            result[first] = lines[first].copy(
                text = cap.text.trim() + lines[first].text,
                x = cap.x,
                paragraphStart = true,
            )
            // The rest of the wrapped lines only look indented because the cap was in the way.
            for (j in beside) if (j != first) result[j] = lines[j].copy(x = cap.x)
        }
        return result.filterIndexed { index, _ -> index !in dropped }
    }

    private fun isCap(line: PdfLine, bodyFontSize: Float): Boolean {
        val text = line.text.trim()
        return text.length == 1 && text[0].isLetter() && line.fontSize >= bodyFontSize * MIN_FONT_RATIO
    }
}
