#include <jni.h>
#include <string>
#include "llama.h"

extern "C" JNIEXPORT jstring JNICALL
Java_com_nishu_app_llm_llamacpp_LlamaBridge_buildInfo(JNIEnv *env, jobject) {
    std::string s = std::string("llama.cpp ") + NISHU_LLAMA_TAG + "\n" + llama_print_system_info();
    return env->NewStringUTF(s.c_str());
}
