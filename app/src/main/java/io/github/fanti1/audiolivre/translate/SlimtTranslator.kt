package io.github.fanti1.audiolivre.translate

// Turns a batch of texts in one language into another. Each text may hold several sentences; the engine
// splits and joins them itself.
interface MachineTranslator : AutoCloseable {
    fun translate(texts: List<String>): List<String>
}

// The Firefox translation engine (Bergamot models) run by slimt, in native code: see app/src/main/cpp.
// A route of two models (Spanish → English → Portuguese) runs the whole batch through one, then the other.
class SlimtTranslator private constructor(
    private val service: Long,
    private val models: List<Long>,
) : MachineTranslator {

    override fun translate(texts: List<String>): List<String> {
        if (texts.isEmpty()) return emptyList()
        // Bytes, not Java strings: JNI's string calls speak "modified UTF-8", which mangles emoji and any
        // other character outside the basic plane.
        val input = Array(texts.size) { texts[it].toByteArray(Charsets.UTF_8) }
        val output = nativeTranslate(service, models[0], models.getOrElse(1) { 0L }, input)
        return output.map { it.toString(Charsets.UTF_8) }
    }

    override fun close() {
        models.forEach(::nativeDeleteModel)
        nativeDeleteService(service)
    }

    companion object {
        // The native engine is only in builds made with its sources (see app/build.gradle.kts).
        val isAvailable: Boolean by lazy { runCatching { System.loadLibrary("audiolivre_translate") }.isSuccess }

        // Loads every model of a route, in order. Each folder holds one direction's model, shortlist and
        // vocabulary files, as TranslationModelStore leaves them.
        fun open(store: TranslationModelStore, route: List<ModelPair>): SlimtTranslator {
            check(isAvailable) { "O tradutor não está disponível nesta versão do app." }
            require(route.size in 1..2) { "rota de tradução inválida: ${route.map { it.id }}" }
            val workers = Runtime.getRuntime().availableProcessors().coerceIn(1, MAX_WORKERS)
            val service = nativeNewService(workers)
            val models = ArrayList<Long>()
            try {
                for (pair in route) {
                    fun path(file: ModelFile?) = file?.let { store.fileFor(pair, it).absolutePath }
                    val model = nativeNewModel(
                        path(pair.model)!!,
                        path(pair.vocabulary)!!,
                        path(pair.lexicalShortlist),
                        path(pair.targetVocabulary),
                    )
                    models += model
                }
            } catch (e: Throwable) {
                models.forEach(::nativeDeleteModel)
                nativeDeleteService(service)
                throw e
            }
            return SlimtTranslator(service, models)
        }

        private const val MAX_WORKERS = 4

        @JvmStatic private external fun nativeNewService(workers: Int): Long
        @JvmStatic private external fun nativeDeleteService(service: Long)
        @JvmStatic private external fun nativeNewModel(model: String, vocabulary: String, shortlist: String?, targetVocabulary: String?): Long
        @JvmStatic private external fun nativeDeleteModel(model: Long)
        @JvmStatic private external fun nativeTranslate(service: Long, first: Long, second: Long, inputs: Array<ByteArray>): Array<ByteArray>
    }
}
