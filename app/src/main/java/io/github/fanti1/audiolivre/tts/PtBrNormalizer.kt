package io.github.fanti1.audiolivre.tts

// Turns written Portuguese into the words a narrator says, so the voice never has to guess at digits, symbols,
// abbreviations or acronyms. Pure Kotlin, unit tested, and checked by ear with qa/listen_check.py. It only
// changes what is spoken: the page keeps showing the original text.
object PtBrNormalizer {

    fun normalize(text: String): String {
        var t = TextCleaner.clean(text)
        t = cleanMarks(t)
        // Before the old-spelling pass, which squeezes doubled consonants and would erase the very repeats
        // ("Hmm", "Shhh") that mark a word as a sound. Long before Acronyms, which spells a shouted "SHHHH".
        // The stutter goes first: "N-n-não" is speech, not a sound.
        t = Stutter.speak(t)
        t = Onomatopoeia.speak(t)
        t = ArchaicSpelling.modernize(t)
        t = replaceDates(t)
        t = replaceRanges(t)
        t = normalizeDashes(t)
        t = expandAbbreviations(t)
        t = replaceWebAddresses(t)
        t = replaceMoney(t)
        t = replacePercent(t)
        t = replaceTimes(t)
        t = replacePhones(t)
        t = replaceTemperatures(t)
        t = replaceUnits(t)
        t = replaceMultiplication(t)
        t = replaceSlashNumbers(t)
        t = replaceDottedNumbers(t)
        t = replaceOrdinals(t)
        t = replaceRomanNumerals(t)
        t = replaceSymbols(t)
        t = Acronyms.speak(t)
        t = replaceNumbers(t)
        return tidy(t, text)
    }

    // ---- quotes, footnote markers ----

    private val DOUBLE_QUOTES = Regex("[“”„‟«»]")
    private val SINGLE_QUOTES = Regex("[‘’‚‛´`]")
    // A footnote number glued to the end of a word ("surpreendentes1,") would be read as part of it.
    private val FOOTNOTE = Regex("""(?<=\p{Ll}{3})\d{1,2}(?=[\s.,;:!?)"']|$)""")
    private val REFERENCE_MARKS = Regex("[*†‡]")

    private fun cleanMarks(t: String): String {
        var result = DOUBLE_QUOTES.replace(t, "\"")
        result = SINGLE_QUOTES.replace(result, "'")
        result = FOOTNOTE.replace(result, "")
        return REFERENCE_MARKS.replace(result, "")
    }

    // ---- dashes ----

    private val SPACED_DASH = Regex("""\s+[—–]\s+""")
    private val EDGE_DASH = Regex("""^[—–]\s*|\s*[—–]$""")

    // Supertonic turns "—" into "-", which it reads badly; a comma keeps the pause.
    private fun normalizeDashes(t: String) = EDGE_DASH.replace(SPACED_DASH.replace(t, ", "), "")

    // ---- abbreviations ----

    private val ABBREVIATIONS: List<Pair<Regex, String>> = listOf(
        """V\. ?Ex\.ª""" to "Vossa Excelência",
        """V\. ?Exa\.""" to "Vossa Excelência",
        """p\. ?ex\.""" to "por exemplo",
        """a\. ?C\.""" to "antes de Cristo",
        """d\. ?C\.""" to "depois de Cristo",
        """Srta\.""" to "senhorita",
        """Sras\.""" to "senhoras",
        """Sra\.""" to "senhora",
        """Srs\.""" to "senhores",
        """Sr\.""" to "senhor",
        """Dras\.""" to "doutoras",
        """Dra\.""" to "doutora",
        """Drs\.""" to "doutores",
        """Dr\.""" to "doutor",
        """Profa\.""" to "professora",
        """Profs\.""" to "professores",
        """Prof\.""" to "professor",
        """Exma\.""" to "excelentíssima",
        """Exmo\.""" to "excelentíssimo",
        """Pe\.""" to "padre",
        """Sto\.""" to "santo",
        """Sta\.""" to "santa",
        """Cia\.""" to "companhia",
        """Ltda\.""" to "limitada",
        """[Aa]v\.""" to "avenida",
        """[Pp]ágs\.""" to "páginas",
        """[Pp]ág\.""" to "página",
        """[Cc]ap\.""" to "capítulo",
        """[Ss]éc\.""" to "século",
        """[Aa]rt\.""" to "artigo",
        """[Vv]ol\.""" to "volume",
        """[Tt]el\.""" to "telefone",
        """[Aa]prox\.""" to "aproximadamente",
        """[Nn]\.?º""" to "número",
        """etc\.(?=\s*$)""" to "etcétera.",
        """etc\.""" to "etcétera",
    ).map { (pattern, replacement) -> Regex("""(?<![\p{L}\p{N}])$pattern""") to replacement }

    // "D. Glória" is dona, "D. Pedro" is dom.
    private val DONA_DOM = Regex("""(?<![\p{L}\p{N}])D\.\s?(?=(\p{Lu}\p{L}*))""")
    private val FEMININE_NAMES_NOT_IN_A = setOf("inês", "isabel", "beatriz", "raquel", "carmen", "leonor", "ruth", "guiomar", "carmo", "luz", "mercedes")

    private fun expandAbbreviations(t: String): String {
        val expanded = ABBREVIATIONS.fold(t) { acc, (regex, replacement) -> regex.replace(acc, Regex.escapeReplacement(replacement)) }
        return DONA_DOM.replace(expanded) { m ->
            val name = m.groupValues[1].lowercase()
            if (name.endsWith("a") || name in FEMININE_NAMES_NOT_IN_A) "dona " else "dom "
        }
    }

    // ---- e-mail and web addresses ----

    private val EMAIL = Regex("""(?<![\p{L}\p{N}.])([\p{L}\p{N}._+-]+)@([\p{L}\p{N}-]+(?:\.[\p{L}\p{N}-]+)+)""")
    private val URL = Regex(
        """(?i)(?<![\p{L}\p{N}@.])(?:https?://)?((?:www\.)?[a-z0-9-]+(?:\.[a-z0-9-]+)*\.(?:com|org|net|gov|edu|br|io|pt|info|app|dev)(?:\.[a-z]{2})?)(/[^\s,;]*)?(?![\p{L}\p{N}])""",
    )
    private val SPOKEN_DOMAIN_PARTS = mapOf("www" to "dáblio dáblio dáblio", "br" to "bê érre", "io" to "i ó", "pt" to "pê tê", "gov" to "góv")

    private fun spokenAddress(address: String): String =
        address.trimEnd('.').split('.').joinToString(" ponto ") { part ->
            SPOKEN_DOMAIN_PARTS[part.lowercase()] ?: part.replace('-', ' ').replace('_', ' ')
        }

    private fun replaceWebAddresses(t: String): String {
        val withEmails = EMAIL.replace(t) { m -> "${spokenAddress(m.groupValues[1])} arroba ${spokenAddress(m.groupValues[2])}" }
        return URL.replace(withEmails) { m ->
            val path = m.groupValues[2].trim('/').split('/').filter { it.isNotEmpty() }
            (listOf(spokenAddress(m.groupValues[1])) + path.map { spokenAddress(it) }).joinToString(" barra ")
        }
    }

    // ---- money ----

    private enum class Currency(val singular: String, val plural: String, val feminine: Boolean) {
        REAL("real", "reais", false),
        DOLLAR("dólar", "dólares", false),
        EURO("euro", "euros", false),
        POUND("libra", "libras", true),
    }

    private const val AMOUNT = "\\d{1,3}(?:\\.\\d{3})+(?:,\\d{1,2})?|\\d+(?:,\\d{1,2})?"
    private const val CURRENCY_SYMBOL = "R\\$|US\\$|U\\$|€|£|\\$"
    // "R$ 2,5 milhões" is "dois vírgula cinco milhões de reais", not two reais and fifty cents followed by "milhões".
    private val MONEY_SCALED = Regex("($CURRENCY_SYMBOL)\\s?(\\d+(?:,\\d+)?)\\s+(mil|milhão|milhões|bilhão|bilhões|trilhão|trilhões)(?![\\p{L}])")
    private val MONEY_PREFIX = Regex("($CURRENCY_SYMBOL)\\s?($AMOUNT)(?!\\d)")
    private val MONEY_SUFFIX = Regex("(?<![\\p{L}\\d])($AMOUNT)\\s?€")

    private fun currencyOf(symbol: String) = when (symbol) {
        "R$" -> Currency.REAL
        "€" -> Currency.EURO
        "£" -> Currency.POUND
        else -> Currency.DOLLAR
    }

    private fun replaceMoney(t: String): String {
        val scaled = MONEY_SCALED.replace(t) { m ->
            val currency = currencyOf(m.groupValues[1])
            val parts = m.groupValues[2].split(",")
            val number = spellNumber(parts[0], parts.getOrNull(1), feminine = m.groupValues[3] == "mil" && currency.feminine)
                ?: return@replace m.value
            val of = if (m.groupValues[3] == "mil") "" else " de"
            "$number ${m.groupValues[3]}$of ${currency.plural}"
        }
        val prefixed = MONEY_PREFIX.replace(scaled) { m -> spellMoney(m.groupValues[2], currencyOf(m.groupValues[1])) ?: m.value }
        return MONEY_SUFFIX.replace(prefixed) { m -> spellMoney(m.groupValues[1], Currency.EURO) ?: m.value }
    }

    private fun spellMoney(amount: String, currency: Currency): String? {
        val parts = amount.split(",")
        val whole = parts[0].replace(".", "").toLongOrNull() ?: return null
        val cents = parts.getOrNull(1)?.let { (if (it.length == 1) it + "0" else it.take(2)).toInt() } ?: 0
        if (whole > MAX_SPELLED) return null
        val words = mutableListOf<String>()
        if (whole > 0 || cents == 0) {
            val noun = if (whole == 1L) currency.singular else currency.plural
            // "um milhão de reais", but "um milhão e quinhentos mil reais".
            val of = if (whole >= 1_000_000 && whole % 1_000_000 == 0L) " de" else ""
            words += "${cardinal(whole, currency.feminine)}$of $noun"
        }
        if (cents > 0) words += "${cardinal(cents.toLong())} ${if (cents == 1) "centavo" else "centavos"}"
        return words.joinToString(" e ")
    }

    // ---- percent ----

    private val PERCENT = Regex("""(?<![\p{L}\d])(\d{1,3}(?:\.\d{3})+|\d+)(?:,(\d+))?\s?%""")

    private fun replacePercent(t: String): String = PERCENT.replace(t) { m ->
        val number = spellNumber(m.groupValues[1], m.groupValues[2].ifEmpty { null }, feminine = false) ?: return@replace m.value
        "$number por cento"
    }

    // ---- dates ----

    // A period right after the year ends the sentence ("em 12/03/1987."); only ".5" would continue the number.
    private val DATE = Regex("""(?<![\d/.-])(\d{1,2})([/.-])(\d{1,2})\2(\d{4}|\d{2})(?![\d/-]|\.\d)""")
    private val MONTHS = listOf(
        "janeiro", "fevereiro", "março", "abril", "maio", "junho", "julho", "agosto", "setembro", "outubro", "novembro", "dezembro",
    )

    private fun replaceDates(t: String): String = DATE.replace(t) { m ->
        val day = m.groupValues[1].toInt()
        val month = m.groupValues[3].toInt()
        if (day !in 1..31 || month !in 1..12) return@replace m.value
        val dayWords = if (day == 1) "primeiro" else cardinal(day.toLong())
        "$dayWords de ${MONTHS[month - 1]} de ${cardinal(m.groupValues[4].toLong())}"
    }

    // ---- ranges ("1914-1918", "páginas 45-52", "2-1") ----

    private val RANGE = Regex("""(?<![\d.,/:\-–])(\d{1,4})\s?[-–—]\s?(\d{1,4})(?![\d/:\-–]|[.,]\d)""")

    private fun isYear(digits: String) = digits.length == 4 && digits.toInt() in 1000..2100

    private fun replaceRanges(t: String): String = RANGE.replace(t) { m ->
        val (a, b) = m.destructured
        val isRange = (a.length <= 3 && b.length <= 3) || (isYear(a) && (isYear(b) || b.length == 2))
        if (isRange) "$a a $b" else m.value
    }

    // ---- times ----

    private val TIME_H_MIN = Regex("""(?<![\p{L}\d])(\d{1,2})h(\d{2})(?![\p{L}\d])""")
    private val TIME_H = Regex("""(?<![\p{L}\d])(\d{1,2})\s?h(?![\p{L}\d])""")
    // "3:16" is a Bible verse or a score, not a time, unless a time preposition introduces it.
    private val TIME_COLON = Regex(
        """(?<![\p{L}])(às|as|das|pelas|até|desde|entre)\s+(\d{1,2}):(\d{2})(?!\d)""",
        RegexOption.IGNORE_CASE,
    )

    private fun replaceTimes(t: String): String {
        var result = TIME_H_MIN.replace(t) { m -> spellTime(m.groupValues[1], m.groupValues[2]) ?: m.value }
        result = TIME_COLON.replace(result) { m ->
            spellTime(m.groupValues[2], m.groupValues[3])?.let { "${m.groupValues[1]} $it" } ?: m.value
        }
        return TIME_H.replace(result) { m -> spellTime(m.groupValues[1], null) ?: m.value }
    }

    private fun spellTime(hoursText: String, minutesText: String?): String? {
        val hours = hoursText.toInt()
        val minutes = minutesText?.toInt() ?: 0
        if (hours > 23 || minutes > 59) return null
        val hourWords = "${cardinal(hours.toLong(), feminine = true)} ${if (hours == 1) "hora" else "horas"}"
        if (minutes == 0) return hourWords
        return "$hourWords e ${cardinal(minutes.toLong())} ${if (minutes == 1) "minuto" else "minutos"}"
    }

    // ---- phone numbers: digit by digit, as people say them ----

    private val PHONE = Regex("""(?:\((\d{2})\)\s?)?(?<![\d.,])(\d{4,5})-(\d{4})(?!\d)""")

    private fun replacePhones(t: String): String = PHONE.replace(t) { m ->
        val groups = listOfNotNull(m.groupValues[1].ifEmpty { null }?.let { cardinal(it.toLong()) }) +
            listOf(m.groupValues[2], m.groupValues[3]).map { digits -> digits.map { DIGIT_WORDS[it - '0'] }.joinToString(" ") }
        groups.joinToString(", ")
    }

    // ---- temperatures and angles ----

    private val TEMPERATURE = Regex("""(?<![\p{L}\d])([-−]?)(\d+(?:,\d+)?)\s?[°º]\s?([CF])(?![\p{L}])""")
    private val DEGREES = Regex("""(?<![\p{L}\d])([-−]?)(\d+(?:,\d+)?)\s?°""")

    private fun degrees(sign: String, number: String) = "${if (sign.isNotEmpty()) "menos " else ""}$number ${if (number == "1") "grau" else "graus"}"

    private fun replaceTemperatures(t: String): String {
        val scaled = TEMPERATURE.replace(t) { m ->
            degrees(m.groupValues[1], m.groupValues[2]) + if (m.groupValues[3] == "C") " Celsius" else " Fahrenheit"
        }
        return DEGREES.replace(scaled) { m -> degrees(m.groupValues[1], m.groupValues[2]) }
    }

    // ---- units ----

    private val UNIT_WORDS = mapOf(
        "km/h" to ("quilômetro por hora" to "quilômetros por hora"),
        "m/s" to ("metro por segundo" to "metros por segundo"),
        "km²" to ("quilômetro quadrado" to "quilômetros quadrados"),
        "km2" to ("quilômetro quadrado" to "quilômetros quadrados"),
        "m²" to ("metro quadrado" to "metros quadrados"),
        "m2" to ("metro quadrado" to "metros quadrados"),
        "cm²" to ("centímetro quadrado" to "centímetros quadrados"),
        "m³" to ("metro cúbico" to "metros cúbicos"),
        "m3" to ("metro cúbico" to "metros cúbicos"),
        "kg" to ("quilo" to "quilos"),
        "km" to ("quilômetro" to "quilômetros"),
        "cm" to ("centímetro" to "centímetros"),
        "mm" to ("milímetro" to "milímetros"),
        "mg" to ("miligrama" to "miligramas"),
        "ml" to ("mililitro" to "mililitros"),
        "min" to ("minuto" to "minutos"),
        "seg" to ("segundo" to "segundos"),
        "m" to ("metro" to "metros"),
        "g" to ("grama" to "gramas"),
        "l" to ("litro" to "litros"),
        "L" to ("litro" to "litros"),
    )
    private val UNIT = Regex(
        """(?<![\p{L}\d])(\d{1,3}(?:\.\d{3})+(?:,\d+)?|\d+(?:,\d+)?)\s?(""" +
            UNIT_WORDS.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) } +
            """)(?![\p{L}\d²³])""",
    )

    private fun replaceUnits(t: String): String = UNIT.replace(t) { m ->
        val (singular, plural) = UNIT_WORDS.getValue(m.groupValues[2])
        "${m.groupValues[1]} ${if (m.groupValues[1] == "1") singular else plural}"
    }

    // ---- "3 x 1" (a score) and "2 x 3 metros" (a size) ----

    private val MULTIPLICATION = Regex("""(\d+(?:,\d+)?)\s?[xX×]\s?(\d+(?:,\d+)?)(\s+(?:metros?|centímetros?|milímetros?|quilômetros?|polegadas?|pixels?))?""")

    private fun replaceMultiplication(t: String): String = MULTIPLICATION.replace(t) { m ->
        val size = m.groupValues[3].isNotEmpty()
        "${m.groupValues[1]} ${if (size) "por" else "a"} ${m.groupValues[2]}${m.groupValues[3]}"
    }

    // ---- "3/4", "Lei 8.069/1990", "24/7" ----

    private val NUMBERED_OF_YEAR = Regex("""(?<![\d/])(\d{1,3}(?:\.\d{3})+|\d{1,5})/(\d{4})(?![\d/])""")
    private val FRACTION = Regex("""(?<![\d/.,])(\d{1,3})/(\d{1,3})(?![\d/])""")
    private val DENOMINATORS = listOf("", "", "meio", "terço", "quarto", "quinto", "sexto", "sétimo", "oitavo", "nono", "décimo")

    private fun replaceSlashNumbers(t: String): String {
        val laws = NUMBERED_OF_YEAR.replace(t) { m -> "${m.groupValues[1]} de ${m.groupValues[2]}" }
        return FRACTION.replace(laws) { m ->
            val numerator = m.groupValues[1].toInt()
            val denominator = m.groupValues[2].toInt()
            when {
                numerator == 1 && denominator == 2 -> "meio"
                numerator >= 1 && denominator in 2..10 && numerator < denominator ->
                    "${cardinal(numerator.toLong())} ${DENOMINATORS[denominator]}${if (numerator > 1) "s" else ""}"
                else -> "${m.groupValues[1]} por ${m.groupValues[2]}"
            }
        }
    }

    // ---- "versão 2.0" (a dot that isn't a thousands separator) ----

    private val DOTTED = Regex("""(?<![\d.,])(\d+)\.(\d+)(?![\d.,]*\d)""")

    private fun replaceDottedNumbers(t: String): String = DOTTED.replace(t) { m ->
        val (whole, part) = m.destructured
        if (whole.length <= 3 && part.length == 3) return@replace m.value
        val spoken = if (part.length <= 2 && !part.startsWith("0")) cardinal(part.toLong()) else part.map { DIGIT_WORDS[it - '0'] }.joinToString(" ")
        "$whole ponto $spoken"
    }

    // ---- ordinals ----

    private val ORDINAL = Regex("""(?<![\p{L}\d])(\d{1,3})\.?([ºª])""")

    private fun replaceOrdinals(t: String): String = ORDINAL.replace(t) { m ->
        val value = m.groupValues[1].toInt()
        if (value == 0) m.value else ordinal(value, feminine = m.groupValues[2] == "ª")
    }

    // ---- roman numerals ----

    // Case-insensitive for "CAPÍTULO" through RegexOption (Kotlin adds Unicode case folding; an inline flag
    // wouldn't); the numeral itself must still be in capitals, which the replacement checks.
    private val ROMAN_KEYWORD = Regex(
        """((?:capítulo|parte|livro|volume|tomo|seção|secção|ato|cena|canto|século|artigo|fase|episódio)\s+)""" +
            """([IVXLCDM]+)(?![\p{L}\d])""",
        RegexOption.IGNORE_CASE,
    )
    // Regnal numbers ("Pedro II", "Luís XIV") only use I/V/X, which keeps "Vitamina C" or "Tamanho M" untouched.
    private val ROMAN_REGNAL = Regex("""(?<![\p{L}])(\p{Lu}\p{Ll}+)(\s+)([IVX]+)(?![\p{L}\d])""")
    private val ROMAN_ALONE = Regex("""^([IVXLCDM]+)(\.?)$""")
    // Numerals outside a "Capítulo …" phrase, as in a table of contents ("XLIV O primeiro filho"). Tokens with
    // C, D or M only count from four letters on, so acronyms like "CD" or "MC" stay acronyms.
    private val ROMAN_STANDALONE = Regex("""(?<![\p{L}\p{N}])([IVXL]{2,}|[IVXLCDM]{4,})(?![\p{L}\p{N}])""")
    private val NOT_ROMAN = setOf("XL", "LI", "LX", "XXL", "XXX")
    private val NOT_A_NAME = setOf(
        "tipo", "classe", "grupo", "série", "sala", "nível", "vitamina", "anexo", "item", "bloco", "setor", "andar",
        "turma", "categoria", "modelo", "versão", "plano", "quadro", "figura", "tabela", "letra", "raio", "fase",
    )

    private fun replaceRomanNumerals(t: String): String {
        ROMAN_ALONE.matchEntire(t.trim())?.let { m ->
            val value = romanValue(m.groupValues[1]) ?: return t
            return cardinal(value.toLong()) + m.groupValues[2]
        }
        var result = ROMAN_KEYWORD.replace(t) { m ->
            if (m.groupValues[2].any { it.isLowerCase() }) return@replace m.value
            val value = romanValue(m.groupValues[2]) ?: return@replace m.value
            m.groupValues[1] + cardinal(value.toLong())
        }
        result = ROMAN_REGNAL.replace(result) { m ->
            val value = romanValue(m.groupValues[3]) ?: return@replace m.value
            val word = m.groupValues[1]
            val spelled = when {
                word.lowercase() in NOT_A_NAME -> cardinal(value.toLong())
                value <= 10 -> ordinal(value, feminine = false)
                else -> cardinal(value.toLong())
            }
            word + m.groupValues[2] + spelled
        }
        return ROMAN_STANDALONE.replace(result) { m ->
            if (m.value in NOT_ROMAN) return@replace m.value
            romanValue(m.value)?.let { cardinal(it.toLong()) } ?: m.value
        }
    }

    private val ROMAN_DIGITS = mapOf('I' to 1, 'V' to 5, 'X' to 10, 'L' to 50, 'C' to 100, 'D' to 500, 'M' to 1000)

    private fun romanValue(roman: String): Int? {
        var total = 0
        for (i in roman.indices) {
            val value = ROMAN_DIGITS[roman[i]] ?: return null
            val next = roman.getOrNull(i + 1)?.let { ROMAN_DIGITS[it] } ?: 0
            total += if (value < next) -value else value
        }
        // Round-tripping rejects malformed numerals like "IIII", "VX" or "IC".
        return total.takeIf { it in 1..3999 && toRoman(it) == roman }
    }

    private fun toRoman(value: Int): String {
        val symbols = listOf(
            1000 to "M", 900 to "CM", 500 to "D", 400 to "CD", 100 to "C", 90 to "XC",
            50 to "L", 40 to "XL", 10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I",
        )
        var rest = value
        val sb = StringBuilder()
        for ((amount, symbol) in symbols) {
            while (rest >= amount) {
                sb.append(symbol)
                rest -= amount
            }
        }
        return sb.toString()
    }

    // ---- symbols ----

    private val SYMBOLS: List<Pair<Regex, String>> = listOf(
        Regex("""(?<=\d)\s?\+\s?(?=\d)""") to " mais ",
        Regex("""\s?=\s?""") to " igual a ",
        Regex("""\s?&\s?""") to " e ",
        Regex("""§+\s?""") to "parágrafo ",
        Regex("""\s?@\s?""") to " arroba ",
        Regex("""(?<![\p{L}])e/ou(?![\p{L}])""") to "e ou",
        Regex("""(?<=\p{L})/(?=\p{L})""") to " ",
        Regex("""(?<![\p{L}\p{N}])[-−](?=\d)""") to "menos ",
        Regex("""[#_|~^\\<>{}\[\]°¹²³]""") to " ",
    )

    private fun replaceSymbols(t: String): String = SYMBOLS.fold(t) { acc, (regex, replacement) -> regex.replace(acc, replacement) }

    // ---- plain numbers ----

    private val NUMBER = Regex("""(?<![\p{L}\d])(\d{1,3}(?:\.\d{3})+|\d+)(?:,(\d+))?(?![\p{L}\d])""")
    private val NEXT_WORD = Regex("""^\s+(\p{L}+)""")

    private fun replaceNumbers(t: String): String = NUMBER.replace(t) { m ->
        val nextWord = NEXT_WORD.find(t.substring(m.range.last + 1))?.groupValues?.get(1)
        val feminine = nextWord != null && isFeminine(nextWord)
        spellNumber(m.groupValues[1], m.groupValues[2].ifEmpty { null }, feminine) ?: m.value
    }

    private fun spellNumber(integer: String, fraction: String?, feminine: Boolean): String? {
        val whole = spellInteger(integer.replace(".", ""), if (fraction == null) feminine else false) ?: return null
        if (fraction == null) return whole
        val decimals = if (fraction.length <= 2 && !fraction.startsWith("0")) {
            cardinal(fraction.toLong())
        } else {
            fraction.map { DIGIT_WORDS[it - '0'] }.joinToString(" ")
        }
        return "$whole vírgula $decimals"
    }

    private fun spellInteger(digits: String, feminine: Boolean): String? {
        if (digits.isEmpty()) return null
        if (digits.length > 1 && digits.startsWith("0")) return digits.map { DIGIT_WORDS[it - '0'] }.joinToString(" ")
        val value = digits.toLongOrNull() ?: return digits.map { DIGIT_WORDS[it - '0'] }.joinToString(" ")
        if (value > MAX_SPELLED) return digits.map { DIGIT_WORDS[it - '0'] }.joinToString(" ")
        return cardinal(value, feminine)
    }

    // ---- gender of the counted noun ----

    // The word after a number decides "duas"/"dois" only when it is the counted noun; "de 1939 a 1945" or
    // "3 a 1 para o time" must not turn "a" or "para" into a feminine noun.
    private val FUNCTION_WORDS = setOf(
        "a", "à", "as", "às", "o", "os", "e", "ou", "de", "da", "das", "do", "dos", "em", "na", "nas", "no", "nos",
        "para", "pra", "pela", "pelas", "pelo", "pelos", "com", "por", "que", "se", "sem", "sob", "sobre", "entre",
        "até", "após", "ante", "contra", "desde", "cada", "mas", "nem", "como", "já", "era", "foi", "é", "está",
        "estava", "uma", "um", "ao", "aos", "numa", "num", "mais", "menos",
    )
    private val MASCULINE_ENDING_IN_A = setOf(
        "dia", "dias", "mapa", "mapas", "problema", "problemas", "sistema", "sistemas", "programa", "programas",
        "planeta", "planetas", "clima", "climas", "idioma", "idiomas", "tema", "temas", "poema", "poemas",
        "drama", "dramas", "cinema", "cinemas", "telefonema", "telefonemas", "dilema", "dilemas", "esquema",
        "esquemas", "pijama", "pijamas", "sofá", "sofás", "cometa", "cometas", "diploma", "diplomas", "lema",
        "lemas", "profeta", "profetas", "pirata", "piratas", "papa", "papas", "gorila", "gorilas", "guarda",
        "guardas", "mapa-múndi", "atleta", "atletas", "artista", "artistas", "jornalista", "jornalistas",
    )
    private val FEMININE_WORDS = setOf(
        "vez", "vezes", "hora", "horas", "libra", "libras", "noite", "noites", "mulher", "mulheres", "pessoa",
        "pessoas", "página", "páginas", "semana", "semanas", "parte", "partes", "gente", "mão", "mãos", "flor",
        "flores", "cor", "cores", "dor", "dores", "lei", "leis", "nuvem", "nuvens", "tarde", "tardes", "morte",
        "mortes", "árvore", "árvores", "ponte", "pontes", "chave", "chaves", "voz", "vozes", "luz", "luzes", "cruz",
        "cruzes", "rede", "redes", "parede", "paredes", "torre", "torres", "fonte", "fontes", "mente", "mentes",
        "arte", "artes", "classe", "classes", "frase", "frases", "base", "bases", "fase", "fases", "face", "faces",
        "ave", "aves", "série", "séries", "espécie", "espécies", "ordem", "ordens", "imagem", "imagens",
    )

    private fun isFeminine(word: String): Boolean {
        val w = word.lowercase()
        if (w in FUNCTION_WORDS || w in MASCULINE_ENDING_IN_A) return false
        if (w.endsWith("grama") || w.endsWith("gramas")) return false
        if (w in FEMININE_WORDS) return true
        if (w.endsWith("ção") || w.endsWith("ções") || w.endsWith("dade") || w.endsWith("dades")) return true
        if (w.endsWith("gem") || w.endsWith("gens")) return true
        return w.endsWith("a") || w.endsWith("as") || w.endsWith("ã") || w.endsWith("ãs")
    }

    // ---- final tidy ----

    private val OPEN_PAREN = Regex("""\s*\(\s*""")
    private val CLOSE_PAREN = Regex("""\s*\)""")
    private val SPACE_BEFORE_PUNCTUATION = Regex("""\s+([,.;:!?…])""")
    private val COMMA_BEFORE_PUNCTUATION = Regex(""",\s*(?=[,.;:!?…])""")
    private val LEADING_PUNCTUATION = Regex("""^[\s,;:.]+""")
    private val DIGIT_DASH_DIGIT = Regex("""(?<=\d)-(?=\d)""")
    private val WHITESPACE = Regex("""\s+""")
    private val SENTENCE_END = setOf('.', '!', '?', '…', '"')

    private fun tidy(t: String, original: String): String {
        var result = OPEN_PAREN.replace(t, ", ")
        result = CLOSE_PAREN.replace(result, ",")
        result = DIGIT_DASH_DIGIT.replace(result, " ")
        result = WHITESPACE.replace(result, " ")
        result = SPACE_BEFORE_PUNCTUATION.replace(result, "$1")
        result = COMMA_BEFORE_PUNCTUATION.replace(result, "")
        result = LEADING_PUNCTUATION.replace(result, "").trim()
        // "1/2 litro." became "meio litro."; the voice phrases a sentence better when it starts like one.
        val first = original.trimStart().firstOrNull()
        val isSentence = first != null && (first.isDigit() || first.isUpperCase()) && original.trimEnd().lastOrNull() in SENTENCE_END
        return if (isSentence) result.replaceFirstChar { it.uppercaseChar() } else result
    }

    // ---- spelling ----

    private const val MAX_SPELLED = 999_999_999_999L

    private val DIGIT_WORDS = listOf("zero", "um", "dois", "três", "quatro", "cinco", "seis", "sete", "oito", "nove")
    private val UNITS = listOf(
        "zero", "um", "dois", "três", "quatro", "cinco", "seis", "sete", "oito", "nove", "dez", "onze", "doze",
        "treze", "quatorze", "quinze", "dezesseis", "dezessete", "dezoito", "dezenove",
    )
    private val TENS = listOf("", "", "vinte", "trinta", "quarenta", "cinquenta", "sessenta", "setenta", "oitenta", "noventa")
    private val HUNDREDS = listOf(
        "", "cento", "duzentos", "trezentos", "quatrocentos", "quinhentos", "seiscentos", "setecentos", "oitocentos", "novecentos",
    )

    fun cardinal(value: Long, feminine: Boolean = false): String {
        if (value == 0L) return "zero"
        val billions = (value / 1_000_000_000).toInt()
        val millions = (value / 1_000_000 % 1000).toInt()
        val thousands = (value / 1000 % 1000).toInt()
        val units = (value % 1000).toInt()

        val groups = mutableListOf<Pair<String, Int>>()
        if (billions > 0) groups += (if (billions == 1) "um bilhão" else "${belowThousand(billions, false)} bilhões") to billions
        if (millions > 0) groups += (if (millions == 1) "um milhão" else "${belowThousand(millions, false)} milhões") to millions
        if (thousands > 0) groups += (if (thousands == 1) "mil" else "${belowThousand(thousands, feminine)} mil") to thousands
        if (units > 0) groups += belowThousand(units, feminine) to units

        val sb = StringBuilder(groups.first().first)
        for (i in 1 until groups.size) {
            val (words, groupValue) = groups[i]
            // "mil e quinhentos", "mil e vinte", but "mil duzentos e trinta".
            val joinWithE = i == groups.lastIndex && (groupValue < 100 || groupValue % 100 == 0)
            sb.append(if (joinWithE) " e " else " ").append(words)
        }
        return sb.toString()
    }

    private fun belowThousand(value: Int, feminine: Boolean): String {
        if (value == 100) return "cem"
        val words = mutableListOf<String>()
        val hundreds = value / 100
        val rest = value % 100
        if (hundreds > 0) {
            val word = HUNDREDS[hundreds]
            words += if (feminine && hundreds > 1) word.dropLast(2) + "as" else word
        }
        if (rest > 0) {
            words += if (rest < 20) {
                unit(rest, feminine)
            } else {
                val tens = TENS[rest / 10]
                if (rest % 10 == 0) tens else "$tens e ${unit(rest % 10, feminine)}"
            }
        }
        return words.joinToString(" e ")
    }

    private fun unit(value: Int, feminine: Boolean): String = when {
        feminine && value == 1 -> "uma"
        feminine && value == 2 -> "duas"
        else -> UNITS[value]
    }

    private val ORDINAL_UNITS = listOf("", "primeiro", "segundo", "terceiro", "quarto", "quinto", "sexto", "sétimo", "oitavo", "nono")
    private val ORDINAL_TENS = listOf(
        "", "décimo", "vigésimo", "trigésimo", "quadragésimo", "quinquagésimo", "sexagésimo", "septuagésimo", "octogésimo", "nonagésimo",
    )
    private val ORDINAL_HUNDREDS = listOf(
        "", "centésimo", "ducentésimo", "trecentésimo", "quadringentésimo", "quingentésimo", "sexcentésimo",
        "septingentésimo", "octingentésimo", "nongentésimo",
    )

    fun ordinal(value: Int, feminine: Boolean = false): String {
        val words = if (value == 1000) {
            listOf("milésimo")
        } else {
            listOf(ORDINAL_HUNDREDS[value / 100 % 10], ORDINAL_TENS[value / 10 % 10], ORDINAL_UNITS[value % 10])
                .filter { it.isNotEmpty() }
        }
        return words.joinToString(" ") { if (feminine) it.dropLast(1) + "a" else it }
    }
}
