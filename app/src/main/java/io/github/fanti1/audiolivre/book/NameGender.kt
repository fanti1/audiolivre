package io.github.fanti1.audiolivre.book

// Who is talking when the book says "disse Capitu" instead of "disse ela". Without this almost every line of
// dialogue falls back to the narrator's voice: in Dom Casmurro only 13 of 4802 sentences ever reached a
// character voice, because Machado names his speakers rather than pointing at them.
//
// Getting this wrong is worse than not guessing, so a name only counts when the answer is clear: either it is
// a name we know, or Portuguese morphology settles it. Anything ambiguous stays unknown and keeps the
// narrator's voice.
object NameGender {

    // Names whose ending would mislead the rules below, plus the ones that turn up constantly in Brazilian
    // books. Written without accents; lookup strips them.
    private val FEMALE = setOf(
        "carmen", "carmem", "ines", "isabel", "raquel", "rachel", "beatriz", "esther", "ester", "judith",
        "judite", "ruth", "rute", "mercedes", "dolores", "lourdes", "jaqueline", "jacqueline", "elisabeth",
        "solange", "irene", "iracema", "astrid", "ingrid", "margareth", "marta", "conceicao", "assuncao",
        "encarnacion", "consuelo", "socorro", "rosario", "pilar", "noemi", "miriam", "mirian", "sol",
        "capitu", "madalena", "magdalena", "lucy", "nancy", "doroteia", "clotilde", "matilde", "gertrudes",
        "agnes", "helen", "eleanor", "jane", "mary", "anne", "kate", "grace", "joan", "flor", "mabel",
    )
    private val MALE = setOf(
        "jose", "andre", "luca", "nicola", "garcia", "jeremias", "elias", "matias", "tobias", "isaias",
        "zacarias", "josue", "noe", "moises", "jonas", "lucas", "tomas", "thomas", "nicolas", "dimas",
        "cosme", "jorge", "vicente", "clemente", "felipe", "filipe", "duarte", "aires", "peres", "mendes",
        "fernandes", "alvares", "bento", "bentinho", "escobar", "ezequiel", "daniel", "gabriel", "rafael",
        "manuel", "manoel", "miguel", "joel", "abel", "samuel", "ismael", "raul", "saul", "ivan", "ruben",
        "ruy", "rui", "heitor", "nestor", "vitor", "victor", "hector", "cesar", "oscar", "omar", "edgar",
        "jean", "john", "james", "charles", "george", "louis", "luis", "luiz", "pedro", "paulo", "joao",
        "carlos", "antonio", "francisco", "marcos", "mateus", "tiago", "santiago", "sebastiao", "simao",
        "estevao", "cristovao", "benjamim", "joaquim", "serafim", "amorim", "nelson", "wilson", "edson",
        "anderson", "jefferson", "washington", "newton", "milton", "hamilton", "ariel", "nicolau",
    )

    // A name ending in one of these is male even though it ends in -a or another misleading letter.
    private val MALE_ENDINGS = listOf("o", "os", "or", "om", "im", "um", "son", "ton", "el", "ar", "er", "ur", "u")
    private val FEMALE_ENDINGS = listOf("a", "as", "ia", "ina", "ete", "ette", "elle", "ie")

    private val ACCENTS = mapOf(
        'á' to 'a', 'à' to 'a', 'ã' to 'a', 'â' to 'a', 'é' to 'e', 'ê' to 'e', 'í' to 'i', 'ó' to 'o',
        'õ' to 'o', 'ô' to 'o', 'ú' to 'u', 'ü' to 'u', 'ç' to 'c', 'ñ' to 'n',
    )

    // Titles that name the speaker's gender directly, whatever follows them.
    private val MALE_TITLES = setOf("sr", "senhor", "dom", "dr", "doutor", "padre", "frei", "prof", "professor", "seu")
    private val FEMALE_TITLES = setOf("sra", "senhora", "srta", "senhorita", "dona", "dra", "doutora", "madre", "irma", "professora")

    fun of(name: String): GenderHint {
        val key = fold(name)
        // Titles are a closed list, so they are safe to read even when short ("Sr", "Dr").
        if (key in MALE_TITLES) return GenderHint.MALE
        if (key in FEMALE_TITLES) return GenderHint.FEMALE
        // Below that an initial ("J.") or a fragment would be gendered by its last letter alone.
        if (key.length < 3) return GenderHint.UNKNOWN
        if (key in MALE) return GenderHint.MALE
        if (key in FEMALE) return GenderHint.FEMALE
        // A diminutive keeps its root's gender: "Bentinho" is Bento, "Mariazinha" is Maria.
        undiminished(key)?.let { root ->
            if (root in MALE) return GenderHint.MALE
            if (root in FEMALE) return GenderHint.FEMALE
        }
        MALE_ENDINGS.firstOrNull { key.endsWith(it) }?.let { male ->
            // "-a" beats "-o" only when it is the longer match, so "Mario" stays male and "Maria" female.
            val female = FEMALE_ENDINGS.filter { key.endsWith(it) }.maxByOrNull { it.length }
            return if (female != null && female.length > male.length) GenderHint.FEMALE else GenderHint.MALE
        }
        if (FEMALE_ENDINGS.any { key.endsWith(it) }) return GenderHint.FEMALE
        return GenderHint.UNKNOWN
    }

    // "Bentinho" -> "bento", "Mariazinha" -> "maria", "Luisinha" -> "luisa".
    private fun undiminished(key: String): String? = when {
        key.endsWith("zinho") -> key.dropLast(5) + "o"
        key.endsWith("zinha") -> key.dropLast(5) + "a"
        key.endsWith("inho") -> key.dropLast(4) + "o"
        key.endsWith("inha") -> key.dropLast(4) + "a"
        else -> null
    }

    private fun fold(name: String): String =
        buildString(name.length) {
            for (c in name.lowercase()) {
                val plain = ACCENTS[c] ?: c
                if (plain.isLetter()) append(plain)
            }
        }
}
