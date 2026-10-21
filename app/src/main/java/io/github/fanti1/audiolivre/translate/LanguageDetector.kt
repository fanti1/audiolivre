package io.github.fanti1.audiolivre.translate

import java.util.Locale

// Every book ends up in Brazilian Portuguese: it is the only language the voices speak.
const val TARGET_LANGUAGE = "pt"

private val PT_BR = Locale("pt", "BR")

// "inglês", "espanhol", "chinês (Tradicional)": the name a Brazilian reader knows the language by.
fun languageName(code: String): String =
    Locale.forLanguageTag(code).getDisplayName(PT_BR).ifBlank { code }

// Tells which language a book is written in, from its own text. Latin-script languages are told apart by
// their most common short words, the ones every page is full of; other scripts mostly name their language
// outright. A book it cannot place is left to the reader to name.
object LanguageDetector {

    private const val SAMPLE_PARAGRAPHS = 400
    private const val MIN_WORDS = 30
    private const val MIN_SHARE = 0.04
    private const val MIN_LEAD = 2.0

    // Words frequent in one language and rare in its neighbours. Words two close languages share ("de",
    // "que", "a") are left out, since they only blur the difference between Portuguese and Spanish.
    private val COMMON_WORDS: Map<String, Set<String>> = mapOf(
        "pt" to words("não uma com os ele ela mas foi seu sua também ao pelo pela isso você muito então são das dos num numa lhe há já ainda depois dele dela tinha estava porém nem tão"),
        "es" to words("el los las del con pero muy también son su sus lo yo ella fue había y cuando esto pues donde sin"),
        "en" to words("the and of to that was he it for with his on be at by had not but from they she you this have which her were their"),
        "fr" to words("les des et est une dans qui pas pour sur au avec elle sont mais nous vous était avait leur cette"),
        "it" to words("gli della di che è non ma anche sono alla nel questo questa ancora suo ci io lui"),
        "de" to words("der die das und ist nicht ein eine zu den mit sich auf für dem von sie er ich auch wie aber noch wenn nur"),
        "nl" to words("het een van dat niet zijn op voor met hij zij ze ik maar ook als bij nog wat er naar"),
        "ca" to words("els amb però molt també és són seva va hi aquest aquesta això quan"),
        "ro" to words("și în este că din care pentru fost lui sunt această acest foarte"),
        "pl" to words("nie się jest że jak ale tak jego jej już był była przez dla tylko"),
        "cs" to words("je že jak ale jsem jeho její pro tak už bylo byl jako které"),
        "sv" to words("och att det som på är av för med till har inte han hon jag var om men sig så"),
        "da" to words("og det til på af med han hun jeg ikke som var der men har sig så"),
        "nb" to words("og det til på av med han hun jeg ikke som var der men har seg så"),
        "fi" to words("ja ei että hän oli mutta kun niin tai kuin myös ole jo sen hänen minä nyt vain"),
        "hu" to words("az és hogy egy van volt meg már csak mint még azt neki ezt nagyon"),
        "tr" to words("ve bir bu için ile çok gibi ama daha ben sen var değil kadar olarak sonra"),
        "id" to words("dan yang ini itu dengan untuk tidak dari dalam akan pada juga saya ada kami mereka atau"),
    )

    private val WORD = Regex("""\p{L}+""")

    // The language code a book is written in, or null when the text is too short or too mixed to tell.
    fun detect(paragraphs: List<String>): String? {
        val sample = sampleOf(paragraphs)
        byScript(sample)?.let { return it }
        val words = WORD.findAll(sample.lowercase(Locale.ROOT)).map { it.value }.toList()
        if (words.size < MIN_WORDS) return null
        val scores = COMMON_WORDS.mapValues { (_, common) -> words.count { it in common }.toDouble() / words.size }
        val ranked = scores.entries.sortedByDescending { it.value }
        val best = ranked[0]
        val second = ranked[1].value
        if (best.value < MIN_SHARE || best.value < second * MIN_LEAD) return null
        return best.key
    }

    // Paragraphs spread over the whole book: a preface or a list of characters alone can mislead.
    private fun sampleOf(paragraphs: List<String>): String {
        val text = paragraphs.filter { it.length > 40 }
        val step = (text.size / SAMPLE_PARAGRAPHS).coerceAtLeast(1)
        return text.filterIndexed { i, _ -> i % step == 0 }.take(SAMPLE_PARAGRAPHS).joinToString("\n")
    }

    private fun byScript(text: String): String? {
        val counts = HashMap<Character.UnicodeScript, Int>()
        var letters = 0
        for (c in text) {
            if (!Character.isLetter(c)) continue
            letters++
            val script = Character.UnicodeScript.of(c.code)
            counts[script] = (counts[script] ?: 0) + 1
        }
        if (letters == 0) return null
        val (script, count) = counts.maxBy { it.value }
        if (script == Character.UnicodeScript.LATIN || count < letters / 2) return null
        return when (script) {
            Character.UnicodeScript.CYRILLIC -> cyrillic(text)
            Character.UnicodeScript.GREEK -> "el"
            Character.UnicodeScript.ARABIC -> when {
                text.any { it in "ےٹڈڑں" } -> "ur"
                text.any { it in "پچژگی" } -> "fa"
                else -> "ar"
            }
            Character.UnicodeScript.HEBREW -> "he"
            // Japanese mixes kanji with kana; a book with kana in it is Japanese whatever the kanji count.
            Character.UnicodeScript.HAN -> if ((counts[Character.UnicodeScript.HIRAGANA] ?: 0) > 0) "ja" else "zh"
            Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> "ja"
            Character.UnicodeScript.HANGUL -> "ko"
            Character.UnicodeScript.THAI -> "th"
            Character.UnicodeScript.DEVANAGARI -> "hi"
            Character.UnicodeScript.BENGALI -> "bn"
            Character.UnicodeScript.TAMIL -> "ta"
            Character.UnicodeScript.TELUGU -> "te"
            Character.UnicodeScript.KANNADA -> "kn"
            Character.UnicodeScript.MALAYALAM -> "ml"
            Character.UnicodeScript.GUJARATI -> "gu"
            else -> null
        }
    }

    // Letters only some Cyrillic alphabets have.
    private fun cyrillic(text: String): String = when {
        text.any { it in "іїєґІЇЄҐ" } -> "uk"
        text.any { it in "ўЎ" } -> "be"
        text.any { it in "ђћџљњЂЋЏЉЊ" } -> "sr"
        text.count { it == 'ъ' } > text.count { it == 'ы' } -> "bg"
        else -> "ru"
    }

    private fun words(list: String): Set<String> = list.split(' ').filter { it.isNotEmpty() }.toSet()
}
