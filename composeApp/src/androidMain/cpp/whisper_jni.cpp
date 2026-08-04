#include <jni.h>
#include <string>
#include <vector>
#include <algorithm>
#include <cmath>
#include <thread>
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

    // Beam search (whisper.cpp sets beam_size=5 automatically for this strategy) is
    // markedly less prone than greedy decoding to getting stuck repeating a phrase -
    // confirmed on-device: greedy decoding produced verbatim tripled/doubled phrases
    // on some real-speech segments (e.g. "mit normalen Pausen, mit normalen Pausen,
    // mit normalen Pausen."), one of which also ballooned to 45s of decode time since
    // max_tokens was unbounded. max_tokens below is a hard backstop on top of that.
    struct whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_BEAM_SEARCH);
    wparams.language = "de";
    wparams.translate = false;
    wparams.print_progress = false;
    wparams.print_realtime = false;
    wparams.n_threads = std::max(1, static_cast<int>(std::thread::hardware_concurrency()));
    wparams.max_tokens = 224;

    // Left at its default (0), audio_ctx makes whisper.cpp always encode the
    // model's full 30s/1500-frame context no matter how short the input is, so
    // every call costs the same regardless of segment length. audio_ctx is in
    // encoder frames (WHISPER_CHUNK_SIZE seconds -> max_ctx frames), so size it
    // to the actual audio length - but generously: too tight a window starves the
    // decoder of acoustic grounding near the end of the segment, which can send it
    // into a token-repetition loop (max_tokens is unbounded by default, so a stuck
    // decode both repeats/garbles the transcript and runs far longer than normal).
    // 256 frames (~5.12s) minimum plus a 3s margin keeps most of the speedup for
    // typical short segments while leaving real headroom.
    const int max_ctx = whisper_n_audio_ctx(ctx);
    const float seconds = static_cast<float>(n) / WHISPER_SAMPLE_RATE;
    const float frames_per_sec = max_ctx / static_cast<float>(WHISPER_CHUNK_SIZE);
    const int min_ctx = 256;
    const int wanted_ctx = std::max(min_ctx, static_cast<int>(std::ceil(seconds * frames_per_sec)) + static_cast<int>(3 * frames_per_sec));
    wparams.audio_ctx = std::min(max_ctx, wanted_ctx);

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