package io.github.fanti1.audiolivre.tts

import java.text.Normalizer

// The sounds books are written with — a shot, a door, a laugh, a cough, a scream — and how the narrator says
// each one.
//
// A voice that reads letters cannot read most of them as written: it spells "Shhhh" as "esse agá agá agá",
// "Grrr" as "tê érre érre", and "Hahaha" collapses into one "ah". Each spoken form below was chosen by ear on
// the app's own voice, sound by sound, on the review page built by qa/onomatopoeia_review.py; sounds still
// waiting for a better form keep the one they had.
//
// Lookup forgives the ways writers stretch and shout a sound: "Buuuum", "BUM!", "Crrrrac", "toc-toc-toc" all
// find their entry.
object Onomatopoeia {

    private class Sound(
        // How the narrator says it.
        val spoken: String,
        // How it is said when the writer drew it out ("Auuuuu", "Aiiii"), if that differs.
        val stretched: String? = null,
        // A scream written long ("Aaaaaah") is read with the writer's length.
        val keepLength: Boolean = false,
        // Reading speed when a sentence is only this sound: laughs run quicker, a howl a touch slower.
        val speed: Float = 1f,
        val stretchedSpeed: Float? = null,
        // Short spellings that are also abbreviations ("gr" grams, "br" the end of a web address, "hm" a
        // hectometre) only count as sounds when the writer stretched them.
        val onlyStretched: Boolean = false,
    )

    private val catalogue = HashMap<String, Sound>()

    // The spellings of an ambiguous sound that are sounds as written — "hmm", "grr", "pff" — as opposed to the
    // two-letter abbreviations they squeeze down to ("mm" millimetres, "hm", "gr").
    private val writtenAsSound = HashSet<String>()

    private fun sound(
        spoken: String,
        vararg written: String,
        stretched: String? = null,
        keepLength: Boolean = false,
        speed: Float = 1f,
        stretchedSpeed: Float? = null,
        onlyStretched: Boolean = false,
    ) {
        val entry = Sound(spoken, stretched, keepLength, speed, stretchedSpeed, onlyStretched)
        for (w in written) {
            catalogue[key(w)] = entry
            if (onlyStretched && w.length >= 3 && squeeze(w).length < w.length) writtenAsSound += w.lowercase()
        }
    }

    init {
        // Read as written, and listed so a doubled sound ("cri-cri", "tum-tum") is read as beats.
        for (same in listOf("bum", "pum", "tum", "tic", "tac", "dim", "dom", "cri", "piu", "plim", "vrum", "zum",
            "crac", "pá", "tá", "blá", "quá")) sound(same, same)

        // Impacts, falls and explosions
        // Picked against human pt-BR readings (qa/onomatopoeia_reference.py): Faber says "bum" close to "bom";
        // people say [bum], which his "brum" matches best.
        sound("brum", "bum", "boom", "bumm")
        sound("pôu", "pou", "pow")
        sound("pófi", "pof", "poft", "plof", "ploft")
        sound("plaft", "plaf")
        sound("tum", "tump", "thump")
        sound("créqui", "crec", "crek", "créc")
        sound("créc", "crack")
        sound("cabrum", "kabrum", "kaboom", "cabum")
        sound("zás", "zas", "zaz")
        // Gunshots
        sound("bangue", "bang")
        sound("pá", "pah")
        sound("ratatá", "ratata", "ratatata", "ratatatata")
        // Doors, clocks, bells, machines
        sound("nhéc", "nhec", "nhéc", "nheque", "creak")
        sound("tóque", "toc", "knock")
        sound("tic", "tick")
        sound("tac", "tock")
        sound("tique-taque", "tic-tac", "tick-tock")
        sound("dim", "ding")
        sound("dom", "dong")
        sound("dim dom", "ding-dong")
        sound("trim, trim", "trim", "trrim")
        sound("plim", "plin")
        sound("clic", "clic", "click")
        sound("bip", "bip", "beep", "bipbip")
        sound("vrum", "vroom", "vrumm")
        sound("zum", "bzz", "buzz")
        sound("zum", "z", "zz", "zzz", onlyStretched = true)
        // Water
        sound("plique", "plic", "plip")
        sound("ploque", "ploc", "plop")
        sound("chuááá", "chua", "chuá")
        sound("isplach", "splash", "splosh")
        sound("glub", "glup")
        // Animals
        sound("au", "au", stretched = "auu", stretchedSpeed = 0.8f)
        sound("au-au", "au-au")
        sound("miáu", "miau", "meow")
        sound("mu", "mu", "moo", keepLength = true)
        sound("mééé", "me", "mé", "be", "bé", onlyStretched = true)
        sound("cocoricó", "cocorico", "cocoricó")
        sound("quá", "qua", "quac", "quack")
        sound("óinque", "oinc", "oink")
        sound("grrrom", "gr", "grr", "grrr", "r", "rr", onlyStretched = true)
        sound("grrrom", "grawr", "growl")
        sound("chiii", "s", "ss", "sss", onlyStretched = true)
        sound("chiii", "hiss")
        // The body
        sound("atchim", "atchoo", "atishoo")
        sound("cófi", "cof", "coff", "cough")
        sound("isnífi", "snif", "sniff")
        sound("buá", "bua", "buá", keepLength = true)
        sound("ic", "hic", "hick")
        sound("árgui", "argh", "agh", "arg", "aargh")
        sound("âgui", "ugh")
        sound("áfi", "af", "aff")
        sound("árfi", "arf")
        sound("pfu", "pf", "pff", "pfff", onlyStretched = true)
        sound("pfu", "pfft")
        sound("brrru", "br", "brr", "brrr", onlyStretched = true)
        sound("nhãm", "nham", "nhami", "yum")
        sound("nhac", "nhoc", "chomp")
        sound("islúrpi", "slurp")
        sound("glupe", "gulp")
        // Voice: hushing, hesitating, agreeing
        sound("psiu", "sh", "shh", "shhh", "psh", "pst", "psst", "shush", "xiu", "chiu")
        sound("hum", "hm", "hmm", "m", "mm", onlyStretched = true)
        sound("hum", "mhm", "humm", "hummm")
        sound("ãrrã", "aham", "ãhã", "uhuh")
        sound("hum-hum", "uhum", "unhum")
        sound("tisc", "tsc", "tsk", "tsctsc", "tsktsk", "tch")
        sound("hein", "huh")
        // Screams, howls, wind and ghosts
        sound("ah", "ah", "aah", "aaah", keepLength = true)
        sound("ai", "ai", stretched = "aaai")
        sound("ui", "ui", keepLength = true)
        sound("ih", "ih", keepLength = true)
        sound("uh", "uh", "uu", keepLength = true)
        sound("oh", "oh", stretched = "ooooh")
        sound("fiu", "fiu", "fiuf", stretched = "fiiiu")
        sound("vuxe", "vush", "whoosh", "swoosh", "fuush")
    }

    // A letter three or more times in a row: Portuguese doubles "rr" and "ss" but never triples anything.
    private val RUN = Regex("""(\p{L})\1{2,}""", RegexOption.IGNORE_CASE)

    // A word, or a sound doubled with hyphens: "toc-toc", "tic-tac", "au-au-au".
    private val TOKEN = Regex("""\p{L}+(?:-\p{L}+)*""")

    // "hahaha", "HEHEHE", "huahuahua", "kkkk", "rsrs": a laugh is a syllable repeated.
    private val LAUGH = Regex("""(?i)^(h?[aeiou]){0,1}(?:(ha|há|he|hé|hi|hí|ho|hó|hua|rá|rs)){2,}h?$""")
    private val LAUGH_K = Regex("""(?i)^k{3,}$""")
    private val BEAT = Regex("(ha|há|he|hé|hi|hí|ho|hó|hua|rá)", RegexOption.IGNORE_CASE)

    // An open vowel written long is read long and clean ("Aaaaaah" lasts 0.8 s where "Ah" lasts 0.4); a nasal
    // one breaks apart ("Nãããão" came out "não, não é um"), and a consonant written long gets spelled.
    private const val OPEN_VOWELS = "aeiouáàâéêíóôú"
    private const val VOWELS = "aeiouáàâãéêíóôõúü"
    private const val MAX_LONG = 6
    private const val ROMAN = "IVXLCDM"

    fun speak(text: String): String = TOKEN.replace(text) { m -> resolve(m.value)?.first ?: fallback(m.value) }

    // The reading speed for a sentence that is nothing but sounds ("Hahaha!", "Auuuu!"), or null when the
    // sentence has words in it too or its sounds are read at the normal pace.
    fun speedOf(text: String): Float? {
        val tokens = TOKEN.findAll(text).map { it.value }.toList()
        if (tokens.isEmpty()) return null
        val speeds = tokens.map { resolve(it)?.second ?: return null }
        return speeds.firstOrNull { it != 1f }
    }

    // A sentence that is just a drawn-out sound — a scream, a long "nãããão" — which the voice would squeeze into
    // a blip unless it is read slower (Prosody does that).
    fun isDrawnOut(text: String): Boolean =
        RUN.findAll(text).any { it.value.first().lowercaseChar() in VOWELS } &&
            text.trim().split(Regex("""\s+""")).size <= 3

    // The spoken form of one token and the speed it wants, or null when it is not a sound.
    private fun resolve(raw: String): Pair<String, Float>? {
        if (raw.all { it in ROMAN }) return null // "VIII", "XXX" are numbers
        if (raw.equals("www", ignoreCase = true)) return null
        // "PA", "SP", "KKK" are abbreviations and Acronyms spells them; a shout is longer or stretched.
        if (raw.length <= 3 && raw.all { it.isUpperCase() } && !RUN.containsMatchIn(raw)) return null
        if ('-' in raw) {
            // A pair with its own reading ("tic-tac" is "tique-taque"), or a sound doubled with hyphens, read as
            // separate beats ("tóque, tóque"). Compound words ("guarda-chuva", "bem-vindo") are not sounds.
            lookup(raw)?.let { return it }
            val parts = raw.split('-').map { lookup(it) ?: return null }
            return parts.joinToString(", ") { it.first } to parts.first().second
        }
        return lookup(raw)
    }

    private fun lookup(raw: String): Pair<String, Float>? {
        laugh(raw)?.let { return it }
        val sound = catalogue[key(raw)] ?: return null
        // A doubled letter is ordinary spelling ("mm" is millimetres); stretched means three in a row, or one
        // of the spellings writers use for the sound itself.
        val wasStretched = RUN.containsMatchIn(raw) || raw.lowercase() in writtenAsSound
        if (sound.onlyStretched && !wasStretched) return null
        val spoken = when {
            wasStretched && sound.stretched != null -> sound.stretched
            sound.keepLength -> lengthen(sound.spoken, raw)
            else -> sound.spoken
        }
        val speed = if (wasStretched && sound.stretchedSpeed != null) sound.stretchedSpeed else sound.speed
        return matchCase(raw, spoken) to speed
    }

    // "Hahaha" collapses into a single "ah" on the voice; "rá rá rá" is heard as a laugh, a little quicker than
    // speech. The beats that came out best differ by laugh: "rá rá rá" without commas, "rê, rê, rê" with them.
    private fun laugh(raw: String): Pair<String, Float>? {
        if (LAUGH_K.matches(raw)) {
            // "KKK" is also an acronym; a laugh typed in k's runs longer or in lower case.
            if (raw.length == 3 && raw.all { it.isUpperCase() }) return null
            val beats = ((raw.length + 1) / 2).coerceIn(2, 4)
            return matchCase(raw, List(beats) { "ká" }.joinToString(" ")) to 1.15f
        }
        val lower = raw.lowercase()
        if (!LAUGH.matches(lower)) return null
        if (lower.startsWith("rs")) return matchCase(raw, "risos") to 1f
        val beats = BEAT.findAll(lower).count().coerceIn(2, 4)
        fun count(vararg c: Char) = lower.count { it in c }
        val (beat, joiner, speed) = when {
            "hua" in lower -> Triple("uá", " ", 1f)
            count('e', 'é') > count('a', 'á') -> Triple("rê", ", ", 1.3f)
            count('i', 'í') > count('a', 'á') -> Triple("rí", " ", 1f)
            count('o', 'ó') > count('a', 'á') -> Triple("rô", " ", 1f)
            else -> Triple("rá", " ", 1.3f)
        }
        return matchCase(raw, List(beats) { beat }.joinToString(joiner)) to speed
    }

    // A sound that is not in the catalogue but was stretched by the writer: keep a long open vowel, squeeze the
    // rest. Whatever has no vowel left cannot be pronounced and would only be spelled, so it is not read.
    private fun fallback(raw: String): String {
        if (!RUN.containsMatchIn(raw) || raw.all { it in ROMAN } || raw.equals("www", ignoreCase = true)) return raw
        val squeezed = RUN.replace(raw) { m ->
            if (m.value.first().lowercaseChar() in OPEN_VOWELS) m.value.take(MAX_LONG) else m.value.first().toString()
        }
        return if (squeezed.lowercase().none { it in VOWELS }) "" else squeezed
    }

    // Keeps the writer's long vowel: "Aaaaah" stays a scream.
    private fun lengthen(spoken: String, raw: String): String {
        val run = RUN.find(raw)?.value ?: return spoken
        val vowel = run.first().lowercaseChar()
        if (vowel !in OPEN_VOWELS) return spoken
        val at = spoken.indexOfFirst { it.lowercaseChar() == vowel }
        if (at < 0) return spoken
        return spoken.substring(0, at) + vowel.toString().repeat(minOf(run.length, MAX_LONG)) + spoken.substring(at + 1)
    }

    // The catalogue key: lower case with every repeated letter squeezed, so "Buuuum", "BUM" and "bum" are the
    // same sound. Accents are kept: "aí" (there) is a different word from "ai" (ouch), and "pá" from "pa".
    private fun key(written: String): String =
        squeeze(Normalizer.normalize(written.lowercase(), Normalizer.Form.NFC).filter { it.isLetter() })

    private fun squeeze(text: String): String = buildString {
        for (c in text) if (isEmpty() || last() != c) append(c)
    }

    private fun matchCase(original: String, spoken: String): String =
        if (original.firstOrNull()?.isUpperCase() == true) spoken.replaceFirstChar { it.uppercaseChar() } else spoken
}
