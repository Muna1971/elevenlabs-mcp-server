package com.muna.livetranslate

import java.io.File

/** Thin wrapper around whisper.cpp. Not thread-safe: use from one thread. */
class WhisperEngine private constructor(private var handle: Long) : AutoCloseable {

    class Result(val language: String, val text: String)

    /** @param language ISO code such as "en", or "auto" to detect it. */
    fun transcribe(samples: FloatArray, language: String): Result? {
        val bytes = nativeTranscribe(handle, samples, language, THREADS) ?: return null
        val raw = String(bytes, Charsets.UTF_8)
        val tab = raw.indexOf('\t')
        return Result(raw.substring(0, tab), raw.substring(tab + 1).trim())
    }

    override fun close() {
        if (handle != 0L) nativeFree(handle)
        handle = 0L
    }

    companion object {
        init {
            System.loadLibrary("nativecore")
        }

        private val THREADS = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

        fun load(model: File): WhisperEngine? =
            nativeInit(model.absolutePath).takeIf { it != 0L }?.let { WhisperEngine(it) }

        /** Whisper is built for ARMv8.2 with FP16; very old phones lack it. */
        fun isCpuSupported(): Boolean = try {
            File("/proc/cpuinfo").readText().let { "asimdhp" in it || "fphp" in it }
        } catch (e: Exception) {
            true
        }

        @JvmStatic private external fun nativeInit(modelPath: String): Long
        @JvmStatic private external fun nativeFree(handle: Long)
        @JvmStatic private external fun nativeTranscribe(
            handle: Long, samples: FloatArray, language: String, threads: Int,
        ): ByteArray?
    }
}
