#include <jni.h>
#include <string>
#include "whisper.h"

namespace {
// whisper.cpp logs every call (including each VAD chunk); keep logcat quiet.
void quiet_log(enum ggml_log_level, const char *, void *) {}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_muna_livetranslate_WhisperEngine_nativeInit(JNIEnv *env, jclass, jstring modelPath) {
    whisper_log_set(quiet_log, nullptr);
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
    // At most two retries, and only when Whisper itself detects a bad decode (e.g. a repetition loop).
    p.temperature_inc = 0.5f;
    p.greedy.best_of = 1;
    // Speech is ~3 words/s: cap the output to what the clip can contain, so a loop cannot run on.
    const int seconds = n / 16000 + 1;
    p.max_tokens = seconds * 8 + 8;
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

// --- Silero voice activity detection (streaming) ---

extern "C" JNIEXPORT jlong JNICALL
Java_com_muna_livetranslate_VoiceDetector_nativeInit(JNIEnv *env, jclass, jstring modelPath) {
    whisper_log_set(quiet_log, nullptr);
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    whisper_vad_context_params vparams = whisper_vad_default_context_params();
    vparams.n_threads = 1;
    vparams.use_gpu = false;
    whisper_vad_context *vctx = whisper_vad_init_from_file_with_params(path, vparams);
    env->ReleaseStringUTFChars(modelPath, path);
    return reinterpret_cast<jlong>(vctx);
}

extern "C" JNIEXPORT void JNICALL
Java_com_muna_livetranslate_VoiceDetector_nativeFree(JNIEnv *, jclass, jlong handle) {
    whisper_vad_free(reinterpret_cast<whisper_vad_context *>(handle));
}

extern "C" JNIEXPORT void JNICALL
Java_com_muna_livetranslate_VoiceDetector_nativeReset(JNIEnv *, jclass, jlong handle) {
    whisper_vad_reset_state(reinterpret_cast<whisper_vad_context *>(handle));
}

/** Highest speech probability (0..1) over the given samples, keeping state between calls. */
extern "C" JNIEXPORT jfloat JNICALL
Java_com_muna_livetranslate_VoiceDetector_nativeSpeechProbability(
        JNIEnv *env, jclass, jlong handle, jfloatArray samples) {
    auto *vctx = reinterpret_cast<whisper_vad_context *>(handle);
    const jsize n = env->GetArrayLength(samples);
    jfloat *data = env->GetFloatArrayElements(samples, nullptr);
    const bool ok = whisper_vad_detect_speech_no_reset(vctx, data, n);
    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
    if (!ok) return 0.0f;
    float best = 0.0f;
    const int count = whisper_vad_n_probs(vctx);
    const float *probs = whisper_vad_probs(vctx);
    for (int i = 0; i < count; ++i) best = probs[i] > best ? probs[i] : best;
    return best;
}
