#include <jni.h>
#include <string>
#include "whisper.h"

extern "C" JNIEXPORT jlong JNICALL
Java_com_muna_livetranslate_WhisperEngine_nativeInit(JNIEnv *env, jclass, jstring modelPath) {
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(modelPath, path);
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_com_muna_livetranslate_WhisperEngine_nativeFree(JNIEnv *, jclass, jlong handle) {
    whisper_free(reinterpret_cast<whisper_context *>(handle));
}

/**
 * Transcribes 16 kHz mono float samples. Returns UTF-8 bytes of "<lang>\t<text>",
 * or null on failure. Bytes (not a jstring) because Whisper can emit sequences
 * that are not valid modified UTF-8.
 */
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_muna_livetranslate_WhisperEngine_nativeTranscribe(
        JNIEnv *env, jclass, jlong handle, jfloatArray samples, jstring language, jint threads) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx == nullptr) return nullptr;

    const jsize n = env->GetArrayLength(samples);
    jfloat *data = env->GetFloatArrayElements(samples, nullptr);
    const char *lang = env->GetStringUTFChars(language, nullptr);

    whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.n_threads = threads;
    p.language = lang;               // "auto" = detect the spoken language
    p.translate = false;
    p.no_context = true;
    p.no_timestamps = true;
    p.single_segment = true;
    p.print_progress = false;
    p.print_realtime = false;
    p.print_special = false;
    p.print_timestamps = false;
    p.suppress_blank = true;
    p.suppress_nst = true;
    // Short clips: shrink the encoder window (30 s by default) for much faster results.
    const int audioCtx = n / 320 + 128;
    p.audio_ctx = audioCtx < 1500 ? audioCtx : 0;

    const int rc = whisper_full(ctx, p, data, n);

    env->ReleaseStringUTFChars(language, lang);
    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
    if (rc != 0) return nullptr;

    std::string text;
    const int segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < segments; ++i) {
        if (whisper_full_get_segment_no_speech_prob(ctx, i) > 0.6f) continue;
        text += whisper_full_get_segment_text(ctx, i);
    }
    const int langId = whisper_full_lang_id(ctx);
    std::string out = (langId >= 0 ? whisper_lang_str(langId) : "");
    out += '\t';
    out += text;

    jbyteArray result = env->NewByteArray(static_cast<jsize>(out.size()));
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(out.size()),
                            reinterpret_cast<const jbyte *>(out.data()));
    return result;
}
