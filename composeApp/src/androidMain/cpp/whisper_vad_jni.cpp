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

extern "C" JNIEXPORT void JNICALL
Java_ai_healthcarepoc_voice_WhisperVad_nativeFeed(JNIEnv *env, jobject /*thiz*/, jlong handle, jfloatArray samples) {
    auto *vctx = reinterpret_cast<struct whisper_vad_context *>(handle);
    jsize n = env->GetArrayLength(samples);
    std::vector<float> buffer(n);
    env->GetFloatArrayRegion(samples, 0, n, buffer.data());
    whisper_vad_detect_speech_no_reset(vctx, buffer.data(), static_cast<int>(buffer.size()));
}

// Returns a flattened [t0_0, t1_0, t0_1, t1_1, ...] array, one (t0, t1) pair per closed
// segment, in seconds - matches how WhisperVad.android.kt's segments() unpacks it.
extern "C" JNIEXPORT jfloatArray JNICALL
Java_ai_healthcarepoc_voice_WhisperVad_nativeSegments(JNIEnv *env, jobject /*thiz*/, jlong handle, jint minSilenceDurationMs) {
    auto *vctx = reinterpret_cast<struct whisper_vad_context *>(handle);

    struct whisper_vad_params params = whisper_vad_default_params();
    params.min_silence_duration_ms = minSilenceDurationMs;

    struct whisper_vad_segments *segments = whisper_vad_segments_from_probs(vctx, params);
    int n = whisper_vad_segments_n_segments(segments);

    std::vector<float> flat;
    flat.reserve(static_cast<size_t>(n) * 2);
    for (int i = 0; i < n; ++i) {
        flat.push_back(whisper_vad_segments_get_segment_t0(segments, i));
        flat.push_back(whisper_vad_segments_get_segment_t1(segments, i));
    }
    whisper_vad_free_segments(segments);

    jfloatArray result = env->NewFloatArray(static_cast<jsize>(flat.size()));
    env->SetFloatArrayRegion(result, 0, static_cast<jsize>(flat.size()), flat.data());
    return result;
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
