package com.muna.livetranslate

import java.io.File

/** Silero voice activity detection (via whisper.cpp): tells speech apart from music and noise. */
class VoiceDetector private constructor(private var handle: Long) : AutoCloseable {

    /** Speech probability 0..1 for the next audio chunk (a multiple of [WINDOW] samples). */
    fun speechProbability(samples: FloatArray): Float = nativeSpeechProbability(handle, samples)

    fun reset() = nativeReset(handle)

    override fun close() {
        if (handle != 0L) nativeFree(handle)
        handle = 0L
    }

    companion object {
        init {
            System.loadLibrary("nativecore")
        }

        /** Silero processes 512-sample (32 ms) windows at 16 kHz. */
        const val WINDOW = 512

        fun load(model: File): VoiceDetector? =
            nativeInit(model.absolutePath).takeIf { it != 0L }?.let { VoiceDetector(it) }

        @JvmStatic private external fun nativeInit(modelPath: String): Long
        @JvmStatic private external fun nativeFree(handle: Long)
        @JvmStatic private external fun nativeReset(handle: Long)
        @JvmStatic private external fun nativeSpeechProbability(handle: Long, samples: FloatArray): Float
    }
}
