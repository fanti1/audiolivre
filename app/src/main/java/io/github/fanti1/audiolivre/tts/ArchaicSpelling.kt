package io.github.fanti1.audiolivre.tts

// Public-domain classics are often typeset in pre-1943 spelling ("elle", "annos", "chamal-a"), which a voice
// reading letter by letter gets wrong ("elle" comes out as the letter L). Only the spoken text is modernized;
// the page keeps the author's spelling. Every rule here is safe on modern text, where doubled consonants,
// "ph", "th" and "y" only appear in loanwords and names, which sound the same without them.
object ArchaicSpelling {

    private val WORDS = mapOf(
        "quiz" to "quis", "ahi" to "aí", "dahi" to "daí", "tambem" to "também", "sómente" to "somente",
        "hontem" to "ontem", "hombro" to "ombro", "humido" to "úmido", "hespanhol" to "espanhol",
        "sahir" to "sair", "sahiu" to "saiu", "sahi" to "saí", "sahia" to "saía", "cahir" to "cair",
        "cahiu" to "caiu", "cahi" to "caí", "cahia" to "caía", "cousa" to "coisa", "cousas" to "coisas",
        "dous" to "dois", "logar" to "lugar", "logares" to "lugares", "egreja" to "igreja", "egrejas" to "igrejas",
        "egual" to "igual", "eguaes" to "iguais", "egualmente" to "igualmente", "assucar" to "açúcar",
        "prompto" to "pronto", "prompta" to "pronta", "promptos" to "prontos", "promptas" to "prontas",
        "extranho" to "estranho", "extranha" to "estranha", "extrangeiro" to "estrangeiro", "mez" to "mês",
        "portuguez" to "português", "francez" to "francês", "inglez" to "inglês", "freguez" to "freguês",
        "cortez" to "cortês", "burguez" to "burguês", "fôra" to "fora",
    )

    private val CONTRACTION = Regex("""(?<![\p{L}])([dDnN]|lh)'(?=\p{L})""")
    // "chamal-a" is today's "chamá-la": the infinitive's r became l before the pronoun, and the vowel is stressed.
    private val ENCLITIC_L = Regex("""(?<![\p{L}])(\p{L}+?)([aeiou])l-(as|a|os|o)(?![\p{L}])""")
    private val WORD = Regex("""\p{L}+""")
    private val DOUBLED = Regex("""([bcdfglmnpt])\1""")
    private val INITIAL_SC = Regex("""^([Ss])c(?=[eiéêí])""")
    private val PLURAL_AES = Regex("""aes$""")
    private val FINAL_AE = Regex("""^(\p{L}{1,3})ae$""")
    private val STRESSED = mapOf('a' to "á", 'e' to "ê", 'i' to "i", 'o' to "ô", 'u' to "u")

    fun modernize(text: String): String {
        var t = CONTRACTION.replace(text, "$1")
        t = ENCLITIC_L.replace(t) { m ->
            m.groupValues[1] + STRESSED.getValue(m.groupValues[2][0]) + "-l" + m.groupValues[3]
        }
        return WORD.replace(t) { m -> modernizeWord(m.value) }
    }

    private fun modernizeWord(word: String): String {
        WORDS[word.lowercase()]?.let { return matchCase(word, it) }
        // All-caps tokens are acronyms or headings; Acronyms decides how those are spoken.
        if (word.length > 1 && word.all { it.isUpperCase() }) return word
        // Two letters are a unit or an abbreviation, never old spelling: squeezing "mm" to "m" read
        // "5 mm" as "cinco metros".
        if (word.length <= 2) return word
        var w = DOUBLED.replace(word, "$1")
        w = INITIAL_SC.replace(w, "$1")
        w = w.replace("ph", "f").replace("Ph", "F").replace("th", "t").replace("Th", "T")
        w = w.replace('y', 'i').replace('Y', 'I')
        w = PLURAL_AES.replace(w, "ais")
        w = FINAL_AE.replace(w, "$1ai")
        return w
    }

    private fun matchCase(original: String, replacement: String): String =
        if (original.first().isUpperCase()) replacement.replaceFirstChar { it.uppercaseChar() } else replacement
}
