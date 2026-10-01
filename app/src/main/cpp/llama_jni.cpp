#include <jni.h>

#include <string>
#include <vector>

#include "ggml-backend.h"
#include "llama.h"
#include "nishu_session.h"

namespace {

std::string to_string(JNIEnv *env, jstring s) {
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

std::string bytes_to_string(JNIEnv *env, jbyteArray arr) {
    jsize n = env->GetArrayLength(arr);
    std::string out(static_cast<size_t>(n), '\0');
    env->GetByteArrayRegion(arr, 0, n, reinterpret_cast<jbyte *>(out.data()));
    return out;
}

std::vector<llama_token> ints_to_tokens(JNIEnv *env, jintArray arr) {
    jsize n = env->GetArrayLength(arr);
    std::vector<llama_token> out(static_cast<size_t>(n));
    env->GetIntArrayRegion(arr, 0, n, reinterpret_cast<jint *>(out.data()));
    return out;
}

nishu::Session *S(jlong h) { return reinterpret_cast<nishu::Session *>(h); }

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *, void *) {
    llama_backend_init();
    return JNI_VERSION_1_6;
}

// The CPU backend is built as several libraries (one per instruction-set level); ggml scores each against this
// device and loads the best. Must run before the first model load.
JNIEXPORT void JNICALL
Java_com_nishu_app_llm_llamacpp_LlamaBridge_loadBackends(JNIEnv *env, jobject, jstring dir) {
    ggml_backend_load_all_from_path(to_string(env, dir).c_str());
}

// Which CPU features the loaded backend was compiled with, e.g. "NEON = 1 | DOTPROD = 1 | ...".
JNIEXPORT jstring JNICALL
Java_com_nishu_app_llm_llamacpp_LlamaBridge_systemInfo(JNIEnv *env, jobject) {
    return env->NewStringUTF(llama_print_system_info());
}

JNIEXPORT jstring JNICALL
Java_com_nishu_app_llm_llamacpp_LlamaBridge_buildInfo(JNIEnv *env, jobject) {
    std::string s = std::string("llama.cpp ") + NISHU_LLAMA_TAG + "\n" + llama_print_system_info();
    return env->NewStringUTF(s.c_str());
}

// Returns the handle, or 0 on failure. `errorOut[0]` receives the reason.
JNIEXPORT jlong JNICALL
Java_com_nishu_app_llm_llamacpp_LlamaBridge_load(JNIEnv *env, jobject, jstring path, jint nCtx,
                                                  jint nThreads, jint nThreadsBatch, jobjectArray errorOut) {
    std::string error;
    nishu::Session *s = nishu::load(to_string(env, path), nCtx, nThreads, nThreadsBatch, error);
    if (s == nullptr && errorOut != nullptr && env->GetArrayLength(errorOut) > 0) {
        env->SetObjectArrayElement(errorOut, 0, env->NewStringUTF(error.c_str()));
    }
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT void JNICALL
Java_com_nishu_app_llm_llamacpp_LlamaBridge_release(JNIEnv *, jobject, jlong h) {
    nishu::release(S(h));
}

JNIEXPORT void JNICALL
Java_com_nishu_app_llm_llamacpp_LlamaBridge_cancel(JNIEnv *, jobject, jlong h) {
    S(h)->cancel = true;
}

JNIEXPORT jint JNICALL
Java_com_nishu_app_llm_llamacpp_LlamaBridge_contextSize(JNIEnv *, jobject, jlong h) {
    return S(h)->n_ctx;
}

JNIEXPORT jintArray JNICALL
Java_com_nishu_app_llm_llamacpp_LlamaBridge_tokenize(JNIEnv *env, jobject, jlong h, jbyteArray text) {
    std::vector<llama_token> t = nishu::tokenize(S(h), bytes_to_string(env, text));
    jintArray out = env->NewIntArray(static_cast<jsize>(t.size()));
    env->SetIntArrayRegion(out, 0, static_cast<jsize>(t.size()), reinterpret_cast<const jint *>(t.data()));
    return out;
}

JNIEXPORT jint JNICALL
Java_com_nishu_app_llm_llamacpp_LlamaBridge_evalPrefix(JNIEnv *env, jobject, jlong h, jintArray tokens) {
    return nishu::eval_prefix(S(h), ints_to_tokens(env, tokens));
}

JNIEXPORT jint JNICALL
Java_com_nishu_app_llm_llamacpp_LlamaBridge_evalTurn(JNIEnv *env, jobject, jlong h, jintArray tokens) {
    return nishu::eval_turn(S(h), ints_to_tokens(env, tokens));
}

// Returns {finishReason, tokens, ttftMicros, totalMicros}. Pieces go to sink.onPiece(byte[]): Boolean.
JNIEXPORT jlongArray JNICALL
Java_com_nishu_app_llm_llamacpp_LlamaBridge_generate(JNIEnv *env, jobject, jlong h, jint mode,
                                                      jint maxTokens, jstring grammar, jint seed,
                                                      jobject sink) {
    jclass cls = env->GetObjectClass(sink);
    jmethodID onPiece = env->GetMethodID(cls, "onPiece", "([B)Z");
    std::string g = grammar != nullptr ? to_string(env, grammar) : std::string();

    auto cb = [&](const std::string &piece) -> bool {
        jbyteArray arr = env->NewByteArray(static_cast<jsize>(piece.size()));
        env->SetByteArrayRegion(arr, 0, static_cast<jsize>(piece.size()),
                                reinterpret_cast<const jbyte *>(piece.data()));
        jboolean keep = env->CallBooleanMethod(sink, onPiece, arr);
        env->DeleteLocalRef(arr);
        return keep == JNI_TRUE && !env->ExceptionCheck();
    };

    nishu::GenStats st = nishu::generate(S(h), mode, maxTokens, g, static_cast<uint32_t>(seed), cb);
    jlong vals[4] = {st.finish, st.tokens, st.ttft_us, st.total_us};
    jlongArray out = env->NewLongArray(4);
    env->SetLongArrayRegion(out, 0, 4, vals);
    return out;
}

JNIEXPORT jboolean JNICALL
Java_com_nishu_app_llm_llamacpp_LlamaBridge_saveState(JNIEnv *env, jobject, jlong h, jstring path,
                                                       jintArray tokens) {
    return nishu::save_state(S(h), to_string(env, path), ints_to_tokens(env, tokens)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_nishu_app_llm_llamacpp_LlamaBridge_loadState(JNIEnv *env, jobject, jlong h, jstring path,
                                                       jintArray expected) {
    return nishu::load_state(S(h), to_string(env, path), ints_to_tokens(env, expected)) ? JNI_TRUE : JNI_FALSE;
}

}  // extern "C"
