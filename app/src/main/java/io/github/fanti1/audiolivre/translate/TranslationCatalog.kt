package io.github.fanti1.audiolivre.translate

import org.json.JSONObject

// One file of a translation model, as Mozilla's registry lists it.
data class ModelFile(val name: String, val location: String, val size: Long, val sha256: String)

// The files that translate one direction ("en" → "pt"). Most pairs share one vocabulary between both sides;
// the CJK ones ship a source and a target vocabulary instead.
data class ModelPair(
    val from: String,
    val to: String,
    val version: String,
    val model: ModelFile,
    val lexicalShortlist: ModelFile?,
    val vocabulary: ModelFile,
    val targetVocabulary: ModelFile?,
) {
    val id: String get() = "$from-$to"
    val files: List<ModelFile> get() = listOfNotNull(model, lexicalShortlist, vocabulary, targetVocabulary)
    val sizeBytes: Long get() = files.sumOf { it.size }
}

// The translation models Firefox uses (Mozilla's "translations-models" collection), reduced to the newest
// usable version of each direction. Almost every model goes to or from English, so any other language reaches
// Portuguese through English: two models, one after the other.
class TranslationCatalog(val pairs: Map<String, ModelPair>) {

    // The models that take a book from `from` to Portuguese, in the order they run; null when there is no way.
    fun route(from: String): List<ModelPair>? {
        if (from == TARGET_LANGUAGE) return emptyList()
        pairs["$from-$TARGET_LANGUAGE"]?.let { return listOf(it) }
        val toEnglish = pairs["$from-en"] ?: return null
        val toPortuguese = pairs["en-$TARGET_LANGUAGE"] ?: return null
        return listOf(toEnglish, toPortuguese)
    }

    // Languages a book can be translated from, by name.
    fun sourceLanguages(): List<String> =
        pairs.values.map { it.from }.filter { it != TARGET_LANGUAGE && route(it) != null }.distinct()
            .sortedBy { languageName(it) }

    companion object {
        // Model formats this app's engine reads: version 1 (tiny) and 2 (base). A later major version may
        // change the architecture, so it is skipped until the engine is updated for it.
        private const val MAX_MAJOR_VERSION = 2
        private val RELEASE_VERSION = Regex("""^\d+(\.\d+)*$""")

        fun parse(json: String): TranslationCatalog {
            val records = JSONObject(json).getJSONArray("data")
            // (pair, version) → fileType → file
            val grouped = LinkedHashMap<Pair<String, String>, MutableMap<String, ModelFile>>()
            for (i in 0 until records.length()) {
                val record = records.getJSONObject(i)
                if (!appliesHere(record.optString("filter_expression"))) continue
                val version = record.optString("version")
                if (!RELEASE_VERSION.matches(version) || major(version) > MAX_MAJOR_VERSION) continue
                val attachment = record.optJSONObject("attachment") ?: continue
                val pair = "${record.getString("fromLang")}-${record.getString("toLang")}"
                val file = ModelFile(
                    name = record.optString("name"),
                    location = attachment.getString("location"),
                    size = attachment.optLong("size"),
                    sha256 = attachment.optString("hash"),
                )
                grouped.getOrPut(pair to version) { HashMap() }[record.getString("fileType")] = file
            }
            val best = HashMap<String, ModelPair>()
            for ((key, files) in grouped) {
                val (id, version) = key
                val (from, to) = id.split('-', limit = 2).let { it[0] to it[1] }
                val model = files["model"] ?: continue
                val vocabulary = files["vocab"] ?: files["srcvocab"] ?: continue
                val pair = ModelPair(from, to, version, model, files["lex"], vocabulary, files["trgvocab"])
                val current = best[id]
                if (current == null || compareVersions(version, current.version) > 0) best[id] = pair
            }
            return TranslationCatalog(best)
        }

        // The registry tags some records for one Firefox build or another. A filter this app does not
        // understand is skipped: it marks a model Firefox itself would not load on Android.
        private fun appliesHere(filter: String): Boolean = when (filter.trim()) {
            "", "env.appinfo.OS == 'Android'" -> true
            else -> false
        }

        private fun major(version: String) = version.substringBefore('.').toIntOrNull() ?: Int.MAX_VALUE

        internal fun compareVersions(a: String, b: String): Int {
            val x = a.split('.').map { it.toIntOrNull() ?: 0 }
            val y = b.split('.').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(x.size, y.size)) {
                val diff = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
                if (diff != 0) return diff
            }
            return 0
        }
    }
}
