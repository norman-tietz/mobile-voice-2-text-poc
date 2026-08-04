#include <jni.h>
#include <string>
#include <vector>
#include "whisper.h"

extern "C" JNIEXPORT jlong JNICALL
Java_ai_healthcarepoc_voice_WhisperEngine_nativeInit(JNIEnv *env, jobject /*thiz*/, jstring modelPath) {
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    struct whisper_context_params cparams = whisper_context_default_params();
    struct whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(modelPath, path);
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT jstring JNICALL
Java_ai_healthcarepoc_voice_WhisperEngine_nativeTranscribe(JNIEnv *env, jobject /*thiz*/, jlong handle, jfloatArray samples) {
    auto *ctx = reinterpret_cast<struct whisper_context *>(handle);

    jsize n = env->GetArrayLength(samples);
    std::vector<float> buffer(n);
    env->GetFloatArrayRegion(samples, 0, n, buffer.data());

    struct whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wparams.language = "de";
    wparams.translate = false;
    wparams.print_progress = false;
    wparams.print_realtime = false;

    whisper_full(ctx, wparams, buffer.data(), static_cast<int>(buffer.size()));

    std::string result;
    int n_segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < n_segments; ++i) {
        result += whisper_full_get_segment_text(ctx, i);
    }

    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_ai_healthcarepoc_voice_WhisperEngine_nativeRelease(JNIEnv *env, jobject /*thiz*/, jlong handle) {
    auto *ctx = reinterpret_cast<struct whisper_context *>(handle);
    whisper_free(ctx);
}