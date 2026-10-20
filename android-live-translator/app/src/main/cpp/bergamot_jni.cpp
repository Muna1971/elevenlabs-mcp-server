// JNI bridge to slimt-sys's C API (Mozilla Bergamot translation models).
#include <jni.h>
#include <cstddef>
#include <string>
#include <vector>

extern "C" {
// Layout must match slimt-sys/bindings/slimt_wrapper.cpp.
struct CTranslation {
    char *source;
    char *target;
    void *alignments;
    size_t alignment_count;
    void *alternatives;
    size_t alternatives_count;
};
typedef void (*slimt_progress_cb)(void *user_data, size_t completed);

void *slimt_service_new(size_t workers, size_t cache_size);
void slimt_service_delete(void *service);
void *slimt_model_new(const char *model, const char *vocabulary, const char *shortlist,
                      const char *ssplit, size_t encoder_layers, size_t decoder_layers,
                      size_t feed_forward_depth, size_t num_heads, const char *target_vocabulary);
void slimt_model_delete(void *model);
CTranslation *slimt_service_translate(void *service, void *model, const char *const *inputs,
                                      size_t count, bool want_alignment, slimt_progress_cb cb,
                                      void *user_data, const void *cancel_flag);
CTranslation *slimt_service_pivot(void *service, void *first, void *second,
                                  const char *const *inputs, size_t count, bool want_alignment,
                                  slimt_progress_cb cb, void *user_data, const void *cancel_flag);
void slimt_translations_delete(CTranslation *results, size_t count);
}

namespace {

std::string utf8(JNIEnv *env, jbyteArray bytes) {
    const jsize n = env->GetArrayLength(bytes);
    std::string s(static_cast<size_t>(n), '\0');
    env->GetByteArrayRegion(bytes, 0, n, reinterpret_cast<jbyte *>(s.data()));
    return s;
}

std::string optional_path(JNIEnv *env, jstring path) {
    if (path == nullptr) return {};
    const char *c = env->GetStringUTFChars(path, nullptr);
    std::string s(c);
    env->ReleaseStringUTFChars(path, c);
    return s;
}

jbyteArray to_bytes(JNIEnv *env, CTranslation *results) {
    if (results == nullptr) return nullptr;
    const std::string out = results[0].target ? results[0].target : "";
    slimt_translations_delete(results, 1);
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(out.size()));
    env->SetByteArrayRegion(arr, 0, static_cast<jsize>(out.size()),
                            reinterpret_cast<const jbyte *>(out.data()));
    return arr;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_muna_livetranslate_BergamotEngine_nativeNewService(JNIEnv *, jclass) {
    return reinterpret_cast<jlong>(slimt_service_new(1, 64));
}

extern "C" JNIEXPORT void JNICALL
Java_com_muna_livetranslate_BergamotEngine_nativeDeleteService(JNIEnv *, jclass, jlong service) {
    slimt_service_delete(reinterpret_cast<void *>(service));
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_muna_livetranslate_BergamotEngine_nativeNewModel(
        JNIEnv *env, jclass, jstring model, jstring vocab, jstring shortlist, jstring targetVocab) {
    const std::string m = optional_path(env, model);
    const std::string v = optional_path(env, vocab);
    const std::string s = optional_path(env, shortlist);
    const std::string t = optional_path(env, targetVocab);
    // Zero layer counts let the wrapper detect the architecture (tiny/base) from the file.
    return reinterpret_cast<jlong>(slimt_model_new(m.c_str(), v.c_str(), s.c_str(), nullptr,
                                                   0, 0, 0, 0, t.empty() ? nullptr : t.c_str()));
}

extern "C" JNIEXPORT void JNICALL
Java_com_muna_livetranslate_BergamotEngine_nativeDeleteModel(JNIEnv *, jclass, jlong model) {
    slimt_model_delete(reinterpret_cast<void *>(model));
}

/** UTF-8 in, UTF-8 out (null on failure). */
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_muna_livetranslate_BergamotEngine_nativeTranslate(
        JNIEnv *env, jclass, jlong service, jlong model, jbyteArray text) {
    const std::string input = utf8(env, text);
    const char *inputs[] = {input.c_str()};
    return to_bytes(env, slimt_service_translate(reinterpret_cast<void *>(service),
                                                 reinterpret_cast<void *>(model), inputs, 1,
                                                 false, nullptr, nullptr, nullptr));
}

/** Translates through two models, e.g. French -> English -> Arabic. */
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_muna_livetranslate_BergamotEngine_nativePivot(
        JNIEnv *env, jclass, jlong service, jlong first, jlong second, jbyteArray text) {
    const std::string input = utf8(env, text);
    const char *inputs[] = {input.c_str()};
    return to_bytes(env, slimt_service_pivot(reinterpret_cast<void *>(service),
                                             reinterpret_cast<void *>(first),
                                             reinterpret_cast<void *>(second), inputs, 1,
                                             false, nullptr, nullptr, nullptr));
}
