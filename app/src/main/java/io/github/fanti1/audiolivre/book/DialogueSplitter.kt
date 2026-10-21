package io.github.fanti1.audiolivre.book

import io.github.fanti1.audiolivre.tts.ArchaicSpelling

object DialogueSplitter {

    private val DIALOGUE_DASHES = setOf('—', '–')
    private val SPEECH_VERBS = setOf(
        "disse", "diz", "perguntou", "pergunta", "respondeu", "responde", "murmurou", "gritou", "sussurrou",
        "falou", "continuou", "exclamou", "retrucou", "indagou", "declarou", "explicou", "replicou", "comentou",
        "acrescentou", "insistiu", "resmungou", "berrou", "balbuciou", "gaguejou", "ordenou", "pediu",
        "concluiu", "interrompeu", "prosseguiu", "completou", "observou", "admitiu", "suspirou", "riu",
        "sorriu", "pensou", "gemeu", "chorou", "rosnou", "cochichou", "anunciou", "avisou", "bradou",
        "emendou", "rebateu", "provocou", "lembrou", "garantiu", "ponderou", "corrigiu", "confessou",
        "afirmou", "protestou", "implorou", "sugeriu", "quis", "questionou", "reclamou", "repetiu",
    )
    private val PRONOUNS = setOf("ele", "ela", "eles", "elas")
    private val WORD_TRIM = charArrayOf(',', '.', ';', ':', '!', '?', '…')
    private val WHITESPACE = Regex("""\s+""")
    private const val NARRATION_VERB_WINDOW = 4
    private const val MIN_QUOTED_SPEECH_WORDS = 3
    // "perguntou ella como se ouvira mal." runs to six; a tail longer than this is the character
    // still talking, not the narrator naming them.
    private const val MAX_TAG_WORDS = 8
    // Machado hangs the tag off the question mark as often as off a comma: "— Que é? perguntou ella."
    private val TAG_BOUNDARY = charArrayOf(',', '?', '!', '…')

    // Letter lookarounds instead of \b: the JVM's \b is ASCII-only (so "avô"/"moça" never match), and the
    // (?U) flag that fixes it there is a syntax error in Android's ICU regex engine.
    // The determiner carries the gender, so it has to be part of the match: "o jovem" and "a jovem" are the
    // same noun. Possessives count too — "perguntou-lhe minha mãe" is how a narrator names her.
    private const val MALE_THE = """(o|um|meu|seu|nosso|este|esse|aquele)"""
    private const val FEMALE_THE = """(a|uma|minha|sua|nossa|esta|essa|aquela)"""
    private const val MALE_NOUNS = """(homem|rapaz|menino|garoto|senhor|velho|jovem|pai|avô|rei|irmão|tio|marido|filho)"""
    private const val FEMALE_NOUNS = """(mulher|moça|menina|garota|senhora|velha|jovem|mãe|avó|rainha|irmã|tia|esposa|filha)"""
    private val MALE_MARKERS = Regex(
        """(?<![\p{L}\p{N}])(ele|$MALE_THE\s+$MALE_NOUNS)(?![\p{L}\p{N}])""",
    )
    private val FEMALE_MARKERS = Regex(
        """(?<![\p{L}\p{N}])(ela|$FEMALE_THE\s+$FEMALE_NOUNS)(?![\p{L}\p{N}])""",
    )

    fun startsWithDialogueDash(text: String): Boolean {
        val trimmed = text.trimStart()
        val first = trimmed.firstOrNull() ?: return false
        if (first in DIALOGUE_DASHES) return true
        // PDFs often degrade the em dash to a plain hyphen; "-chuva" (lowercase) is a split compound instead.
        val second = trimmed.getOrNull(1) ?: return false
        return first == '-' && (second == ' ' || second.isUpperCase())
    }

    fun split(text: String): List<Segment> {
        if (text.isBlank()) return emptyList()
        val raw = if (startsWithDialogueDash(text)) splitDashDialogue(text) else splitQuotes(text)
        return withGenderHints(text, raw)
    }

    private fun splitDashDialogue(text: String): List<Segment> {
        val lead = text.indexOfFirst { !it.isWhitespace() }
        val boundaries = mutableListOf(lead)
        for (i in lead + 1 until text.length) {
            val c = text[i]
            val spaced = text[i - 1].isWhitespace() && (i + 1 >= text.length || text[i + 1].isWhitespace())
            // Real dashes may be set without spaces ("—Não vou—disse ele."); a plain hyphen only counts when
            // spaced, otherwise it's a compound word.
            if (c in DIALOGUE_DASHES || (c == '-' && spaced)) boundaries += i
        }

        val segments = mutableListOf<Segment>()
        var expectNarration = false
        boundaries.forEachIndexed { k, start ->
            val end = trimEnd(text, start, boundaries.getOrElse(k + 1) { text.length })
            if (k == 0) {
                segments += Segment(SegmentKind.SPEECH, start, end, GenderHint.UNKNOWN)
                expectNarration = true
                return@forEachIndexed
            }
            val body = text.substring(start + 1, end).trim()
            when {
                expectNarration && looksLikeNarration(body) -> {
                    segments += Segment(SegmentKind.NARRATION, start, end, GenderHint.UNKNOWN)
                    expectNarration = false
                }
                // A spaced dash that doesn't introduce narration is a pause inside the same speech.
                expectNarration -> segments[segments.lastIndex] = segments.last().copy(end = end)
                else -> {
                    segments += Segment(SegmentKind.SPEECH, start, end, GenderHint.UNKNOWN)
                    expectNarration = true
                }
            }
        }
        return segments.flatMap { splitTrailingTag(text, it) }
    }

    // Many Brazilian novels attach the speech tag with a comma rather than a second dash: Machado writes
    // "— Já acabei, murmurou elle." Without this the tag is part of the speech, so the line has no narration
    // beside it and nothing says who is talking — which is why almost no dialogue ever reached a character
    // voice. The tag is narration and stays in the narrator's voice, as an audiobook reads it.
    private fun splitTrailingTag(text: String, segment: Segment): List<Segment> {
        if (segment.kind != SegmentKind.SPEECH) return listOf(segment)
        val body = text.substring(segment.start, segment.end)
        for (cut in body.indices.reversed()) {
            if (body[cut] !in TAG_BOUNDARY) continue
            val tail = body.substring(cut + 1)
            val words = tail.split(WHITESPACE).map { it.trim(*WORD_TRIM) }.filter { it.isNotEmpty() }
            // A tag is short, opens with a speech verb, and stays lowercase — after "?" or "!" a capital
            // would mean the character simply went on talking ("— Saiu? Disse que voltava.").
            if (words.size !in 1..MAX_TAG_WORDS) continue
            val first = words.first()
            if (!first.first().isLowerCase() || !isSpeechVerb(first)) continue
            var tagStart = segment.start + cut + 1
            while (tagStart < segment.end && text[tagStart].isWhitespace()) tagStart++
            return listOf(
                // The punctuation stays with the speech, so it is still read as one phrase.
                segment.copy(end = segment.start + cut + 1),
                Segment(SegmentKind.NARRATION, tagStart, segment.end, GenderHint.UNKNOWN),
            )
        }
        return listOf(segment)
    }

    // "perguntou-lhe", "disse-me", "respondeu-nos": the pronoun rides on the verb in written Portuguese.
    private fun isSpeechVerb(word: String): Boolean {
        val lower = word.lowercase()
        if (lower in SPEECH_VERBS) return true
        val hyphen = lower.indexOf('-')
        return hyphen > 0 && lower.substring(0, hyphen) in SPEECH_VERBS
    }

    // A lowercase start alone isn't enough: "— Ela era — como dizer — estranha." is one speech with a pause.
    private fun looksLikeNarration(body: String): Boolean {
        val words = body.split(WHITESPACE).map { it.trim(*WORD_TRIM).lowercase() }.filter { it.isNotEmpty() }
        if (words.isEmpty()) return false
        return words[0] in PRONOUNS || words.take(NARRATION_VERB_WINDOW).any { it in SPEECH_VERBS }
    }

    private fun splitQuotes(text: String): List<Segment> {
        val quotes = findQuoteSpans(text)
        val segments = mutableListOf<Segment>()
        var position = 0
        for (quote in quotes) {
            addNarration(text, position, quote.start, segments)
            segments += Segment(SegmentKind.SPEECH, quote.start, quote.end, GenderHint.UNKNOWN)
            position = quote.end
        }
        addNarration(text, position, text.length, segments)
        return segments
    }

    private fun findQuoteSpans(text: String): List<TextSpan> {
        val spans = mutableListOf<TextSpan>()
        var i = 0
        while (i < text.length) {
            val close = when (text[i]) {
                '“' -> '”'
                '«' -> '»'
                '"' -> '"'
                else -> null
            }
            if (close != null) {
                val j = text.indexOf(close, i + 1)
                if (j != -1) {
                    if (isQuotedSpeech(text.substring(i + 1, j), text)) spans += TextSpan(i, j + 1)
                    i = j + 1
                    continue
                }
            }
            i++
        }
        return spans
    }

    // Quotes also wrap titles, terms and citations ("o livro “Dom Casmurro”", a line of verse), which should
    // stay in the narrator's voice. A run of words alone is not enough to call it speech: the quote has to be
    // a finished sentence, or the paragraph around it has to say that somebody is speaking.
    private fun isQuotedSpeech(inner: String, paragraph: String): Boolean {
        val trimmed = inner.trim()
        if (trimmed.none { it.isLetter() }) return false
        if (trimmed.last() in ".!?…") return true
        if (trimmed.split(WHITESPACE).size < MIN_QUOTED_SPEECH_WORDS) return false
        return paragraph.split(WHITESPACE).any { it.trim(*WORD_TRIM).lowercase() in SPEECH_VERBS }
    }

    private fun addNarration(text: String, from: Int, to: Int, into: MutableList<Segment>) {
        var start = from
        while (start < to && text[start].isWhitespace()) start++
        val end = trimEnd(text, start, to)
        if (end > start && text.substring(start, end).any { it.isLetterOrDigit() }) {
            into += Segment(SegmentKind.NARRATION, start, end, GenderHint.UNKNOWN)
        }
    }

    // The speaker tag usually follows the speech ("— … — disse ele."), but may precede it ("Ele gritou: “…”").
    private fun withGenderHints(text: String, segments: List<Segment>): List<Segment> =
        segments.mapIndexed { k, segment ->
            if (segment.kind != SegmentKind.SPEECH) return@mapIndexed segment
            val gender = listOfNotNull(segments.getOrNull(k + 1), segments.getOrNull(k - 1))
                .filter { it.kind == SegmentKind.NARRATION }
                .map { genderOf(text.substring(it.start, it.end)) }
                .firstOrNull { it != GenderHint.UNKNOWN } ?: GenderHint.UNKNOWN
            segment.copy(genderHint = gender)
        }

    // A lead-in paragraph ("Capitu perguntou:") names the speaker of the dialogue that follows it.
    fun leadInGender(narration: String): GenderHint {
        val words = narration.split(WHITESPACE).map { it.trim(*WORD_TRIM).lowercase() }
        // Only when it really introduces speech, so a paragraph that merely mentions someone is ignored.
        if (narration.trimEnd().lastOrNull() != ':' && words.none { it in SPEECH_VERBS }) return GenderHint.UNKNOWN
        // A whole paragraph is far more room for a second person than a speech tag is: "o homem olhava para
        // mim, ouvindo a moça" names both, and either could be the one who speaks next. Guessing there would
        // put the wrong voice on the line, which is worse than leaving it with the narrator.
        val modern = ArchaicSpelling.modernize(narration).lowercase()
        if (MALE_MARKERS.containsMatchIn(modern) && FEMALE_MARKERS.containsMatchIn(modern)) return GenderHint.UNKNOWN
        return genderOf(narration)
    }

    private fun genderOf(narration: String): GenderHint {
        // Classics are typeset in pre-1943 spelling, where "he said" is "disse elle". The markers below are
        // modern, so they are matched against the modernised text — otherwise every Machado dialogue loses
        // its speaker. Names are looked up in the original spelling, which modernising would damage.
        val lower = ArchaicSpelling.modernize(narration).lowercase()
        val male = MALE_MARKERS.find(lower)?.range?.first
        val female = FEMALE_MARKERS.find(lower)?.range?.first
        return when {
            // A pronoun says it outright, so it wins over a name we only recognise by its ending.
            male != null && female != null -> if (male < female) GenderHint.MALE else GenderHint.FEMALE
            male != null -> GenderHint.MALE
            female != null -> GenderHint.FEMALE
            else -> namedSpeaker(narration)
        }
    }

    // "— Não vou — disse Capitu." names the speaker instead of pointing at her. The name next to the speech
    // verb is the speaker; whether that name is a man's or a woman's is NameGender's problem.
    private fun namedSpeaker(narration: String): GenderHint {
        val words = narration.split(WHITESPACE).filter { it.isNotBlank() }
        val bare = words.map { it.trim(*WORD_TRIM) }
        for ((i, word) in bare.withIndex()) {
            if (word.lowercase() !in SPEECH_VERBS) continue
            // The name usually follows the verb ("disse Capitu", "disse o Escobar"); "Capitu disse" also happens.
            val after = (i + 1..minOf(i + 2, bare.lastIndex)).firstNotNullOfOrNull { gendered(bare[it]) }
            if (after != null) return after
            val before = (i - 1 downTo maxOf(i - 2, 0)).firstNotNullOfOrNull { gendered(bare[it]) }
            if (before != null) return before
        }
        return GenderHint.UNKNOWN
    }

    // Only a capitalised word can be a name; "disse a verdade" must not become a woman.
    private fun gendered(word: String): GenderHint? {
        if (word.isEmpty() || !word.first().isUpperCase()) return null
        return NameGender.of(word).takeIf { it != GenderHint.UNKNOWN }
    }

    private fun trimEnd(text: String, start: Int, end: Int): Int {
        var e = end
        while (e > start && text[e - 1].isWhitespace()) e--
        return e
    }
}
