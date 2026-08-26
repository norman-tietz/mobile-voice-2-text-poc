#include <jni.h>
#include <vector>
#include <thread>
#include <algorithm>
#include "whisper.h"

extern "C" JNIEXPORT jlong JNICALL
Java_ai_healthcarepoc_voice_WhisperVad_nativeInit(JNIEnv *env, jobject /*thiz*/, jstring modelPath) {
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    struct whisper_vad_context_params cparams = whisper_vad_default_context_params();
    cparams.n_threads = std::max(1, static_cast<int>(std::thread::hardware_concurrency()));
    struct whisper_vad_context *vctx = whisper_vad_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(modelPath, path);
    return reinterpret_cast<jlong>(vctx);
}

// Classifies exactly the given chunk as speech/non-speech. whisper_vad_detect_speech_no_reset
// overwrites the VAD context's probability buffer with only this call's chunk each time - it
// does NOT accumulate across calls. Only the model's recurrent hidden state carries forward,
// which is what makes repeated per-chunk calls meaningfully informed by prior audio despite the
// probability buffer itself being call-local. So the probabilities must be read back
// immediately after detect_speech_no_reset(), before the next call overwrites them.
extern "C" JNIEXPORT jfloat JNICALL
Java_ai_healthcarepoc_voice_WhisperVad_nativeSpeechProbability(JNIEnv *env, jobject /*thiz*/, jlong handle, jfloatArray samples) {
    auto *vctx = reinterpret_cast<struct whisper_vad_context *>(handle);
    jsize n = env->GetArrayLength(samples);
    std::vector<float> buffer(n);
    env->GetFloatArrayRegion(samples, 0, n, buffer.data());

    if (!whisper_vad_detect_speech_no_reset(vctx, buffer.data(), static_cast<int>(buffer.size()))) {
        return 0.0f;
    }

    const int n_probs = whisper_vad_n_probs(vctx);
    const float *probs = whisper_vad_probs(vctx);
    float max_prob = 0.0f;
    for (int i = 0; i < n_probs; ++i) {
        max_prob = std::max(max_prob, probs[i]);
    }
    return max_prob;
}

extern "C" JNIEXPORT void JNICALL
Java_ai_healthcarepoc_voice_WhisperVad_nativeResetState(JNIEnv *env, jobject /*thiz*/, jlong handle) {
    auto *vctx = reinterpret_cast<struct whisper_vad_context *>(handle);
    whisper_vad_reset_state(vctx);
}

extern "C" JNIEXPORT void JNICALL
Java_ai_healthcarepoc_voice_WhisperVad_nativeRelease(JNIEnv *env, jobject /*thiz*/, jlong handle) {
    auto *vctx = reinterpret_cast<struct whisper_vad_context *>(handle);
    whisper_vad_free(vctx);
}
