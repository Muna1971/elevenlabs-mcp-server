package com.muna.livetranslate

/** Mozilla's offline translation engine (Firefox Translations models via slimt). One thread at a time. */
class BergamotEngine : AutoCloseable {

    private var service = nativeNewService()
    private val models = HashMap<String, Long>()

    /** Loads a downloaded language pair such as "fr-en"; false if it could not be loaded. */
    fun load(pair: String, files: ModelManager.PairFiles): Boolean {
        if (models.containsKey(pair)) return true
        val handle = nativeNewModel(
            files.model.path, files.vocab.path, files.lex.path, files.targetVocab?.path,
        )
        if (handle == 0L) return false
        models[pair] = handle
        return true
    }

    fun isLoaded(pair: String) = models.containsKey(pair)

    fun translate(pair: String, text: String): String? {
        val model = models[pair] ?: return null
        return nativeTranslate(service, model, text.toByteArray())?.toString(Charsets.UTF_8)
    }

    /** source -> English -> target, e.g. pivot("fr-en", "en-ar", ...). */
    fun pivot(firstPair: String, secondPair: String, text: String): String? {
        val first = models[firstPair] ?: return null
        val second = models[secondPair] ?: return null
        return nativePivot(service, first, second, text.toByteArray())?.toString(Charsets.UTF_8)
    }

    override fun close() {
        models.values.forEach { nativeDeleteModel(it) }
        models.clear()
        if (service != 0L) nativeDeleteService(service)
        service = 0L
    }

    companion object {
        init {
            System.loadLibrary("nativecore")
        }

        @JvmStatic private external fun nativeNewService(): Long
        @JvmStatic private external fun nativeDeleteService(service: Long)
        @JvmStatic private external fun nativeNewModel(model: String, vocab: String, shortlist: String, targetVocab: String?): Long
        @JvmStatic private external fun nativeDeleteModel(model: Long)
        @JvmStatic private external fun nativeTranslate(service: Long, model: Long, text: ByteArray): ByteArray?
        @JvmStatic private external fun nativePivot(service: Long, first: Long, second: Long, text: ByteArray): ByteArray?
    }
}
