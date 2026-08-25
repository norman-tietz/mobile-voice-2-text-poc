# Real-Time VAD Segmentation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the RMS-threshold `PauseDetector` with whisper.cpp's built-in Silero VAD, and restructure the Whisper recording pipeline so segment-boundary detection runs in real time instead of inside the same coroutine that blocks on slow `transcribe()` calls.

**Architecture:** Two-stage pipeline: a fast real-time Segmenter coroutine drains captured audio and uses VAD to decide segment boundaries, pushing finalized segments into a new queue; a separate (slow) Transcriber coroutine drains that queue independently. `PauseDetector` is deleted; `TranscriptionSession` shrinks to just the transcribe stage.

**Tech Stack:** Kotlin Multiplatform (Android JNI / iOS cinterop), whisper.cpp's `whisper_vad_*` API (already vendored, no new dependency), Silero VAD ggml model (new small bundled asset).

**Spec:** `docs/superpowers/specs/2026-08-25-vad-segmentation-design.md`

## Global Constraints

- No new native dependency or platform-binding mechanism — only whisper.cpp APIs already vendored and linked on both platforms.
- VAD model load failure is fatal (same severity as today's Whisper model load failure) — no fallback to RMS.
- `minSilenceDurationMs` for the VAD-based segmenter defaults to 500ms (not the RMS path's 1500ms).
- Native/cinterop code is not unit-testable — verified via instrumented tests where existing infra supports it (Android), otherwise compile-check + manual on-device verification (iOS, end-to-end pipeline).

---

## Task 1: VAD model download script and setup docs

**Files:**
- Create: `scripts/download-vad-model.sh`
- Modify: `README.md` (Prerequisites/Setup section)

**Interfaces:**
- Produces: `composeApp/src/androidMain/assets/models/ggml-silero-v6.2.0.bin` and `iosApp/iosApp/Resources/ggml-silero-v6.2.0.bin` on disk after running the script. Both paths are already covered by the existing `.gitignore` patterns (`composeApp/src/androidMain/assets/models/*.bin`, `iosApp/iosApp/Resources/*.bin`) — no gitignore change needed.

- [ ] **Step 1: Create the download script**

Mirror the existing `scripts/download-model.sh` exactly, pointed at the VAD model instead:

```bash
#!/usr/bin/env bash
set -euo pipefail

MODEL_URL="https://huggingface.co/ggml-org/whisper-vad/resolve/main/ggml-silero-v6.2.0.bin"
ANDROID_DEST="composeApp/src/androidMain/assets/models/ggml-silero-v6.2.0.bin"
IOS_DEST="iosApp/iosApp/Resources/ggml-silero-v6.2.0.bin"

mkdir -p "$(dirname "$ANDROID_DEST")" "$(dirname "$IOS_DEST")"

if [ ! -f "$ANDROID_DEST" ]; then
    curl -L "$MODEL_URL" -o "$ANDROID_DEST"
fi

cp "$ANDROID_DEST" "$IOS_DEST"

echo "VAD model ready at $ANDROID_DEST and $IOS_DEST"
```

- [ ] **Step 2: Make it executable**

Run: `chmod +x scripts/download-vad-model.sh`

- [ ] **Step 3: Run it to fetch the model**

Run: `./scripts/download-vad-model.sh`
Expected: `VAD model ready at composeApp/src/androidMain/assets/models/ggml-silero-v6.2.0.bin and iosApp/iosApp/Resources/ggml-silero-v6.2.0.bin`, and both files exist and are non-empty (`ls -la` on each — expect a few MB, not 0 bytes).

- [ ] **Step 4: Update README setup docs**

In `README.md`, in the `## Setup` code block, add the new script call right after the existing one:

```bash
./scripts/download-model.sh
./scripts/download-vad-model.sh
```

Immediately after that code block, add a short paragraph (matching the style of the existing `ggml-small.bin` paragraph):

```markdown
The second script downloads the small Silero VAD model
(`ggml-silero-v6.2.0.bin`, a few MB) used for real-time speech/silence
segmentation, and places a copy at both platforms' expected asset locations.
It's also gitignored — run it once per fresh checkout, same as the model
script above.
```

- [ ] **Step 5: Commit**

```bash
git add scripts/download-vad-model.sh README.md
git commit -m "Add VAD model download script and setup docs"
```

---

## Task 2: Extend ModelPathProvider with the VAD model path

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/ai/healthcarepoc/voice/ModelPathProvider.kt`
- Modify: `composeApp/src/androidMain/kotlin/ai/healthcarepoc/voice/ModelPathProvider.android.kt`
- Modify: `composeApp/src/iosMain/kotlin/ai/healthcarepoc/voice/ModelPathProvider.ios.kt`

**Interfaces:**
- Produces: `ModelPathProvider.resolveVadModelPath(): String`, resolving to a readable on-disk path to `ggml-silero-v6.2.0.bin` on each platform, following the exact same resolution pattern as the existing `resolveModelPath()`.

No unit tests — this class does real file I/O (asset copy on Android, bundle lookup on iOS) and has no existing test coverage today; consistent with that, this task is compile-checked only.

- [ ] **Step 1: Update the commonMain `expect` declaration**

`composeApp/src/commonMain/kotlin/ai/healthcarepoc/voice/ModelPathProvider.kt` — replace the whole file:

```kotlin
package ai.healthcarepoc.voice

expect class ModelPathProvider(context: ApplicationContext) {
    fun resolveModelPath(): String
    fun resolveVadModelPath(): String
}
```

- [ ] **Step 2: Update the Android `actual`**

`composeApp/src/androidMain/kotlin/ai/healthcarepoc/voice/ModelPathProvider.android.kt` — replace the whole file:

```kotlin
package ai.healthcarepoc.voice

import java.io.File

actual class ModelPathProvider actual constructor(private val context: ApplicationContext) {
    actual fun resolveModelPath(): String = resolveAsset("ggml-small.bin")
    actual fun resolveVadModelPath(): String = resolveAsset("ggml-silero-v6.2.0.bin")

    private fun resolveAsset(filename: String): String {
        val dest = File(context.filesDir, filename)
        if (!dest.exists()) {
            context.assets.open("models/$filename").use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return dest.absolutePath
    }
}
```

- [ ] **Step 3: Update the iOS `actual`**

`composeApp/src/iosMain/kotlin/ai/healthcarepoc/voice/ModelPathProvider.ios.kt` — replace the whole file:

```kotlin
package ai.healthcarepoc.voice

import platform.Foundation.NSBundle

actual class ModelPathProvider actual constructor(context: ApplicationContext) {
    actual fun resolveModelPath(): String = resolveBundleResource("ggml-small")
    actual fun resolveVadModelPath(): String = resolveBundleResource("ggml-silero-v6.2.0")

    private fun resolveBundleResource(name: String): String {
        return NSBundle.mainBundle.pathForResource(name, ofType = "bin")
            ?: error("$name.bin not found in app bundle")
    }
}
```

- [ ] **Step 4: Compile both targets**

Run: `./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:compileKotlinIosSimulatorArm64`
Expected: `BUILD SUCCESSFUL`, no errors (existing `expect`/`actual` Beta warnings are fine, pre-existing).

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/ai/healthcarepoc/voice/ModelPathProvider.kt \
        composeApp/src/androidMain/kotlin/ai/healthcarepoc/voice/ModelPathProvider.android.kt \
        composeApp/src/iosMain/kotlin/ai/healthcarepoc/voice/ModelPathProvider.ios.kt
git commit -m "Add VAD model path resolution to ModelPathProvider"
```

---

## Task 3: VoiceActivityDetector interface

**Files:**
- Create: `composeApp/src/commonMain/kotlin/ai/healthcarepoc/voice/VoiceActivityDetector.kt`

**Interfaces:**
- Produces: `VoiceActivityDetector` interface, implemented by `WhisperVad` (Tasks 4–5) and by a test fake (Task 6).

- [ ] **Step 1: Create the interface**

```kotlin
package ai.healthcarepoc.voice

// Thin seam over whisper.cpp's streaming VAD (whisper_vad_detect_speech_no_reset +
// whisper_vad_segments_from_probs) so the segmentation policy (SpeechSegmenter) can be
// unit-tested against a fake, without needing the real VAD model.
interface VoiceActivityDetector {
    // Feed newly captured samples; appends to the VAD's running trace.
    fun feed(samples: FloatArray)

    // Segment boundaries (seconds, relative to the last resetState() call) that can
    // currently be derived from everything fed so far. A segment only appears once its
    // end has been determined by minSilenceDurationMs of trailing low-probability audio
    // - the in-progress trailing segment (still being spoken) does not appear until it
    // closes.
    fun segments(minSilenceDurationMs: Int): List<ClosedFloatingPointRange<Float>>

    // Clears VAD state between recordings.
    fun resetState()
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew :composeApp:compileDebugKotlinAndroid`
Expected: `BUILD SUCCESSFUL` (nothing implements the interface yet, so nothing else can fail).

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/commonMain/kotlin/ai/healthcarepoc/voice/VoiceActivityDetector.kt
git commit -m "Add VoiceActivityDetector interface"
```

---

## Task 4: WhisperVad native wrapper — Android

**Files:**
- Modify: `composeApp/src/androidMain/cpp/CMakeLists.txt`
- Create: `composeApp/src/androidMain/cpp/whisper_vad_jni.cpp`
- Create: `composeApp/src/androidMain/kotlin/ai/healthcarepoc/voice/WhisperVad.kt` (commonMain `expect`)
- Create: `composeApp/src/androidMain/kotlin/ai/healthcarepoc/voice/WhisperVad.android.kt` (Android `actual`)
- Create: `composeApp/src/androidInstrumentedTest/kotlin/ai/healthcarepoc/voice/WhisperVadAndroidTest.kt`

**Interfaces:**
- Consumes: `VoiceActivityDetector` (Task 3); `ModelPathProvider.resolveVadModelPath()` (Task 2, used by the instrumented test's own asset-copy, mirroring `WhisperEngineAndroidTest`'s pattern).
- Produces: `expect class WhisperVad(modelPath: String) : VoiceActivityDetector { fun release() }`, actual on Android backed by `whisper_vad_context`.

This task verifies the spec's flagged risk on real hardware: that streaming `feed()` + repeated `segments()` calls actually return closed segments as expected, using a real recording (not just that it compiles).

- [ ] **Step 1: Add the commonMain `expect` declaration**

`composeApp/src/commonMain/kotlin/ai/healthcarepoc/voice/WhisperVad.kt` (new file):

```kotlin
package ai.healthcarepoc.voice

expect class WhisperVad(modelPath: String) : VoiceActivityDetector {
    override fun feed(samples: FloatArray)
    override fun segments(minSilenceDurationMs: Int): List<ClosedFloatingPointRange<Float>>
    override fun resetState()
    fun release()
}
```

- [ ] **Step 2: Add the VAD JNI source file**

`composeApp/src/androidMain/cpp/whisper_vad_jni.cpp` (new file):

```cpp
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
```

- [ ] **Step 3: Add the new source file to the CMake build**

`composeApp/src/androidMain/cpp/CMakeLists.txt` — change:

```cmake
add_library(whisper_jni SHARED whisper_jni.cpp)
```

to:

```cmake
add_library(whisper_jni SHARED whisper_jni.cpp whisper_vad_jni.cpp)
```

(Same shared library target/`.so` — `WhisperVad` loads it via the same `System.loadLibrary("whisper_jni")` call `WhisperEngine` already makes.)

- [ ] **Step 4: Add the Android `actual`**

`composeApp/src/androidMain/kotlin/ai/healthcarepoc/voice/WhisperVad.android.kt` (new file):

```kotlin
package ai.healthcarepoc.voice

actual class WhisperVad actual constructor(modelPath: String) : VoiceActivityDetector {
    private val handle: Long = run {
        debugLog("WhisperVad.<init>: calling nativeInit, modelPath=$modelPath")
        val result = nativeInit(modelPath)
        check(result != 0L) { "Failed to load VAD model at $modelPath" }
        result
    }

    actual override fun feed(samples: FloatArray) {
        nativeFeed(handle, samples)
    }

    actual override fun segments(minSilenceDurationMs: Int): List<ClosedFloatingPointRange<Float>> {
        val flat = nativeSegments(handle, minSilenceDurationMs)
        return (flat.indices step 2).map { i -> flat[i]..flat[i + 1] }
    }

    actual override fun resetState() {
        nativeResetState(handle)
    }

    actual fun release() {
        nativeRelease(handle)
    }

    private external fun nativeInit(modelPath: String): Long
    private external fun nativeFeed(handle: Long, samples: FloatArray)
    private external fun nativeSegments(handle: Long, minSilenceDurationMs: Int): FloatArray
    private external fun nativeResetState(handle: Long)
    private external fun nativeRelease(handle: Long)

    companion object {
        init {
            System.loadLibrary("whisper_jni")
        }
    }
}
```

- [ ] **Step 5: Build the native library and compile Kotlin**

Run: `./gradlew :composeApp:externalNativeBuildDebug :composeApp:compileDebugKotlinAndroid`
Expected: `BUILD SUCCESSFUL`, no C++ or Kotlin compile errors.

- [ ] **Step 6: Add an on-device instrumented smoke test**

`composeApp/src/androidInstrumentedTest/kotlin/ai/healthcarepoc/voice/WhisperVadAndroidTest.kt` (new file) — mirrors `WhisperEngineAndroidTest.kt`'s asset-copy pattern, reusing the same `sample-de.wav` fixture and `readWavAsFloatMono16k` helper:

```kotlin
package ai.healthcarepoc.voice

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assert.assertTrue
import java.io.File

class WhisperVadAndroidTest {

    @Test
    fun detectsSpeechSegmentsInGermanSample() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val modelFile = File(targetContext.cacheDir, "ggml-silero-v6.2.0.bin")
        targetContext.assets.open("models/ggml-silero-v6.2.0.bin").use { input ->
            modelFile.outputStream().use { output -> input.copyTo(output) }
        }

        val vad = WhisperVad(modelFile.absolutePath)
        val samples = readWavAsFloatMono16k(testContext.assets.open("sample-de.wav"))

        // Feed in capture-sized chunks (1600 samples / 100ms @ 16kHz), matching how
        // AudioCapture delivers audio in production - this is what actually exercises the
        // streaming feed()/segments() path this test exists to verify, not a single
        // whole-file call.
        val chunkSize = 1600
        var offset = 0
        while (offset < samples.size) {
            val end = minOf(offset + chunkSize, samples.size)
            vad.feed(samples.copyOfRange(offset, end))
            offset = end
        }

        val segments = vad.segments(minSilenceDurationMs = 500)
        vad.release()

        assertTrue("expected at least one speech segment, got none", segments.isNotEmpty())
        segments.forEach { range ->
            assertTrue("segment end (${range.endInclusive}) should be after start (${range.start})",
                range.endInclusive > range.start)
        }
    }
}
```

- [ ] **Step 7: Compile the instrumented test**

Run: `./gradlew :composeApp:compileDebugAndroidTestKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Run it on the connected device**

Run: `./gradlew :composeApp:connectedDebugAndroidTest --tests "ai.healthcarepoc.voice.WhisperVadAndroidTest"`
Expected: test passes, confirming the streaming `feed()`/`segments()` sequence actually returns closed segments for real speech audio — this is the spec's flagged implementation risk, now verified rather than assumed.

- [ ] **Step 9: Commit**

```bash
git add composeApp/src/androidMain/cpp/CMakeLists.txt \
        composeApp/src/androidMain/cpp/whisper_vad_jni.cpp \
        composeApp/src/commonMain/kotlin/ai/healthcarepoc/voice/WhisperVad.kt \
        composeApp/src/androidMain/kotlin/ai/healthcarepoc/voice/WhisperVad.android.kt \
        composeApp/src/androidInstrumentedTest/kotlin/ai/healthcarepoc/voice/WhisperVadAndroidTest.kt
git commit -m "Add WhisperVad native wrapper for Android, verified on-device"
```

---

## Task 5: WhisperVad — iOS

**Files:**
- Create: `composeApp/src/iosMain/kotlin/ai/healthcarepoc/voice/WhisperVad.ios.kt`

**Interfaces:**
- Consumes: `VoiceActivityDetector` (Task 3), `WhisperVad` expect declaration (Task 4, Step 1).
- Produces: `WhisperVad` actual on iOS, same public surface as Android.

No iOS instrumented test infra exists in this repo (same situation `WhisperEngine.ios.kt` is already in) — verification here is compile-check only, consistent with that precedent. End-to-end behavior gets verified on-device in Task 8.

- [ ] **Step 1: Add the iOS `actual`**

`composeApp/src/iosMain/kotlin/ai/healthcarepoc/voice/WhisperVad.ios.kt` (new file), following the same cinterop patterns already used in `WhisperEngine.ios.kt` (`memScoped`, `getPointer(this).pointed`/`readValue()` for by-value structs, `usePinned`/`addressOf` for float arrays):

```kotlin
package ai.healthcarepoc.voice

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.getPointer
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.readValue
import kotlinx.cinterop.usePinned
import platform.Foundation.NSProcessInfo
import whispercinterop.whisper_vad_context_params
import whispercinterop.whisper_vad_default_context_params
import whispercinterop.whisper_vad_default_params
import whispercinterop.whisper_vad_detect_speech_no_reset
import whispercinterop.whisper_vad_free
import whispercinterop.whisper_vad_free_segments
import whispercinterop.whisper_vad_init_from_file_with_params
import whispercinterop.whisper_vad_reset_state
import whispercinterop.whisper_vad_segments_from_probs
import whispercinterop.whisper_vad_segments_get_segment_t0
import whispercinterop.whisper_vad_segments_get_segment_t1
import whispercinterop.whisper_vad_segments_n_segments
import kotlin.math.max

@OptIn(ExperimentalForeignApi::class)
actual class WhisperVad actual constructor(modelPath: String) : VoiceActivityDetector {

    private val vctx = memScoped {
        val cparams = whisper_vad_default_context_params().getPointer(this).pointed
        cparams.n_threads = max(1, NSProcessInfo.processInfo.activeProcessorCount.toInt())
        whisper_vad_init_from_file_with_params(modelPath, cparams.readValue())
            ?: error("Failed to load VAD model at $modelPath")
    }

    actual override fun feed(samples: FloatArray) {
        samples.usePinned { pinned ->
            whisper_vad_detect_speech_no_reset(vctx, pinned.addressOf(0), samples.size)
        }
    }

    actual override fun segments(minSilenceDurationMs: Int): List<ClosedFloatingPointRange<Float>> = memScoped {
        val params = whisper_vad_default_params().getPointer(this).pointed
        params.min_silence_duration_ms = minSilenceDurationMs

        val segments = whisper_vad_segments_from_probs(vctx, params.readValue())
            ?: error("whisper_vad_segments_from_probs returned null")
        val n = whisper_vad_segments_n_segments(segments)
        val result = (0 until n).map { i ->
            whisper_vad_segments_get_segment_t0(segments, i)..whisper_vad_segments_get_segment_t1(segments, i)
        }
        whisper_vad_free_segments(segments)
        result
    }

    actual override fun resetState() {
        whisper_vad_reset_state(vctx)
    }

    actual fun release() {
        whisper_vad_free(vctx)
    }
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew :composeApp:compileKotlinIosSimulatorArm64`
Expected: `BUILD SUCCESSFUL`, no cinterop/unresolved-reference errors (confirms the `whisper_vad_*` symbols are indeed exposed through the existing `whisper.def` binding, per the spec's feasibility check).

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/iosMain/kotlin/ai/healthcarepoc/voice/WhisperVad.ios.kt
git commit -m "Add WhisperVad actual for iOS"
```

---

## Task 6: SpeechSegmenter (replaces PauseDetector)

**Files:**
- Create: `composeApp/src/commonMain/kotlin/ai/healthcarepoc/voice/SpeechSegmenter.kt`
- Create: `composeApp/src/commonTest/kotlin/ai/healthcarepoc/voice/SpeechSegmenterTest.kt`

**Interfaces:**
- Consumes: `VoiceActivityDetector` (Task 3).
- Produces:
  ```kotlin
  class SpeechSegmenter(
      vad: VoiceActivityDetector,
      sampleRateHz: Int,
      minSilenceDurationMs: Int = 500
  ) {
      fun accept(samples: FloatArray): List<FloatArray>
      fun flush(): FloatArray?
      fun reset()
  }
  ```
  Used by `App.kt` (Task 8) as the real-time Segmenter stage.

This is the core unit-testable policy logic from the spec — full TDD, tested against a fake `VoiceActivityDetector` so no real model is needed.

- [ ] **Step 1: Write the failing tests**

`composeApp/src/commonTest/kotlin/ai/healthcarepoc/voice/SpeechSegmenterTest.kt` (new file):

```kotlin
package ai.healthcarepoc.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private class FakeVad(
    private val segmentsPerCall: MutableList<List<ClosedFloatingPointRange<Float>>>
) : VoiceActivityDetector {
    val fedChunks = mutableListOf<FloatArray>()
    var resetCalls = 0

    override fun feed(samples: FloatArray) {
        fedChunks.add(samples)
    }

    override fun segments(minSilenceDurationMs: Int): List<ClosedFloatingPointRange<Float>> =
        if (segmentsPerCall.isNotEmpty()) segmentsPerCall.removeAt(0) else emptyList()

    override fun resetState() {
        resetCalls++
    }
}

class SpeechSegmenterTest {

    private val sampleRate = 16_000

    // 100ms chunks @ 16kHz = 1600 samples, matching AudioCapture's real chunk size.
    private fun chunk(value: Float): FloatArray = FloatArray(1600) { value }

    @Test
    fun `accept returns nothing while the VAD has not closed any segment`() {
        val vad = FakeVad(mutableListOf(emptyList(), emptyList()))
        val segmenter = SpeechSegmenter(vad, sampleRate)

        val result1 = segmenter.accept(chunk(0.5f))
        val result2 = segmenter.accept(chunk(0.5f))

        assertEquals(emptyList(), result1)
        assertEquals(emptyList(), result2)
    }

    @Test
    fun `accept returns a newly closed segment sliced from the buffered samples`() {
        // Two 100ms chunks fed (200ms total = 3200 samples). On the second accept()
        // call, the VAD reports one closed segment spanning the first 100ms (0.0-0.1s
        // = samples 0 until 1600) - i.e. only the first chunk's content.
        val vad = FakeVad(mutableListOf(emptyList(), listOf(0.0f..0.1f)))
        val segmenter = SpeechSegmenter(vad, sampleRate)
        val firstChunk = chunk(1.0f)
        val secondChunk = chunk(2.0f)

        segmenter.accept(firstChunk)
        val result = segmenter.accept(secondChunk)

        assertEquals(1, result.size)
        assertEquals(firstChunk.toList(), result[0].toList())
    }

    @Test
    fun `accept does not re-emit a segment already returned in an earlier call`() {
        // First call closes segment [0.0, 0.1]; second call's VAD result still includes
        // that same segment (as whisper_vad_segments_from_probs recomputes from the
        // whole trace each time) plus one new one [0.1, 0.2] - only the new one should
        // come back out.
        val vad = FakeVad(mutableListOf(listOf(0.0f..0.1f), listOf(0.0f..0.1f, 0.1f..0.2f)))
        val segmenter = SpeechSegmenter(vad, sampleRate)
        val firstChunk = chunk(1.0f)
        val secondChunk = chunk(2.0f)

        val firstResult = segmenter.accept(firstChunk)
        val secondResult = segmenter.accept(secondChunk)

        assertEquals(1, firstResult.size)
        assertEquals(1, secondResult.size)
        assertEquals(secondChunk.toList(), secondResult[0].toList())
    }

    @Test
    fun `flush returns the still-open tail after the last emitted segment`() {
        // Real whisper_vad_segments_from_probs() recomputes cumulatively from the whole
        // trace each call, so a segment already closed keeps reappearing in later calls
        // even when nothing new has closed - the fake mirrors that (same list twice)
        // rather than shrinking back to empty, which a real VAD would never do.
        val vad = FakeVad(mutableListOf(listOf(0.0f..0.1f), listOf(0.0f..0.1f)))
        val segmenter = SpeechSegmenter(vad, sampleRate)
        val firstChunk = chunk(1.0f)
        val secondChunk = chunk(2.0f)

        segmenter.accept(firstChunk) // closes [0.0, 0.1] = firstChunk
        segmenter.accept(secondChunk) // no new closed segment; secondChunk stays pending

        val tail = segmenter.flush()

        assertEquals(secondChunk.toList(), tail!!.toList())
    }

    @Test
    fun `flush returns null when everything has already been emitted`() {
        val vad = FakeVad(mutableListOf(listOf(0.0f..0.1f)))
        val segmenter = SpeechSegmenter(vad, sampleRate)

        segmenter.accept(chunk(1.0f)) // closes exactly the one chunk fed so far

        assertNull(segmenter.flush())
    }

    @Test
    fun `reset clears buffered state and resets the VAD`() {
        val vad = FakeVad(mutableListOf(emptyList(), emptyList()))
        val segmenter = SpeechSegmenter(vad, sampleRate)
        segmenter.accept(chunk(1.0f))

        segmenter.reset()

        assertEquals(1, vad.resetCalls)
        assertNull(segmenter.flush())
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "ai.healthcarepoc.voice.SpeechSegmenterTest"`
Expected: compile failure (`SpeechSegmenter` and `VoiceActivityDetector.segments`/`feed`/`resetState` signatures don't have an implementation yet, or `SpeechSegmenter` doesn't exist) — this is the correct RED for a new-class TDD step in a statically typed language, matching how the `resetContext()` interface change was verified earlier in this project.

- [ ] **Step 3: Write the implementation**

`composeApp/src/commonMain/kotlin/ai/healthcarepoc/voice/SpeechSegmenter.kt` (new file):

```kotlin
package ai.healthcarepoc.voice

class SpeechSegmenter(
    private val vad: VoiceActivityDetector,
    private val sampleRateHz: Int,
    private val minSilenceDurationMs: Int = 500
) {
    private val buffer = mutableListOf<Float>()
    private var closedSegmentsEmitted = 0
    private var lastEmittedEndSample = 0

    // Feed one capture chunk. Returns any segment(s) that just closed (a segment only
    // closes once minSilenceDurationMs of trailing non-speech follows it).
    fun accept(samples: FloatArray): List<FloatArray> {
        buffer.addAll(samples.toList())
        vad.feed(samples)

        val closed = vad.segments(minSilenceDurationMs)
        val newlyClosed = closed.drop(closedSegmentsEmitted)
        closedSegmentsEmitted = closed.size

        return newlyClosed.map { range ->
            val startSample = (range.start * sampleRateHz).toInt().coerceIn(0, buffer.size)
            val endSample = (range.endInclusive * sampleRateHz).toInt().coerceIn(startSample, buffer.size)
            lastEmittedEndSample = endSample
            buffer.subList(startSample, endSample).toFloatArray()
        }
    }

    // Force-finalizes whatever's still open (end of recording, no pause reached yet).
    // Bounded worst case if this is pure trailing silence rather than real speech: at
    // most minSilenceDurationMs of audio, once per recording - anything longer would
    // already have closed as its own segment via a prior accept() call.
    fun flush(): FloatArray? {
        if (lastEmittedEndSample >= buffer.size) return null
        return buffer.subList(lastEmittedEndSample, buffer.size).toFloatArray()
    }

    // Clears state between recordings.
    fun reset() {
        buffer.clear()
        closedSegmentsEmitted = 0
        lastEmittedEndSample = 0
        vad.resetState()
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "ai.healthcarepoc.voice.SpeechSegmenterTest"`
Expected: `BUILD SUCCESSFUL`, all 6 tests pass.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/ai/healthcarepoc/voice/SpeechSegmenter.kt \
        composeApp/src/commonTest/kotlin/ai/healthcarepoc/voice/SpeechSegmenterTest.kt
git commit -m "Add SpeechSegmenter, VAD-driven replacement for PauseDetector"
```

---

## Task 7: Simplify TranscriptionSession; delete PauseDetector

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/ai/healthcarepoc/voice/TranscriptionSession.kt`
- Modify: `composeApp/src/commonTest/kotlin/ai/healthcarepoc/voice/TranscriptionSessionTest.kt`
- Delete: `composeApp/src/commonMain/kotlin/ai/healthcarepoc/voice/PauseDetector.kt`
- Delete: `composeApp/src/commonTest/kotlin/ai/healthcarepoc/voice/PauseDetectorTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  interface Transcriber {
      fun transcribe(samples: FloatArray): String
      fun resetContext()
  }

  class TranscriptionSession(transcriber: Transcriber) {
      val segments: List<String>
      fun transcribeSegment(samples: FloatArray)
      fun stop()
  }
  ```
  `stop()` no longer returns `List<String>` (callers read `.segments` directly) and no
  longer force-finalizes a pending buffer — that's now `SpeechSegmenter.flush()`'s job
  (Task 6), called from `App.kt` (Task 8) before `TranscriptionSession.stop()`.
- Consumed by: `App.kt` (Task 8).

- [ ] **Step 1: Write the failing test for the new shape**

Replace `composeApp/src/commonTest/kotlin/ai/healthcarepoc/voice/TranscriptionSessionTest.kt` in full:

```kotlin
package ai.healthcarepoc.voice

import kotlin.test.Test
import kotlin.test.assertEquals

private class FakeTranscriber(private val responses: MutableList<String>) : Transcriber {
    val callArgs = mutableListOf<FloatArray>()
    var resetContextCalls = 0
    override fun transcribe(samples: FloatArray): String {
        callArgs.add(samples)
        return responses.removeAt(0)
    }
    override fun resetContext() {
        resetContextCalls++
    }
}

class TranscriptionSessionTest {

    private val sampleRate = 16_000

    private fun chunk(ms: Int, value: Float): FloatArray {
        val n = sampleRate * ms / 1000
        return FloatArray(n) { value }
    }

    @Test
    fun `no segments before any transcribeSegment call`() {
        val transcriber = FakeTranscriber(mutableListOf())
        val session = TranscriptionSession(transcriber)

        assertEquals(emptyList(), session.segments)
    }

    @Test
    fun `transcribeSegment appends the transcribed text to segments`() {
        val transcriber = FakeTranscriber(mutableListOf("hallo welt"))
        val session = TranscriptionSession(transcriber)

        session.transcribeSegment(chunk(100, 0.5f))

        assertEquals(listOf("hallo welt"), session.segments)
        assertEquals(1, transcriber.callArgs.size)
    }

    @Test
    fun `multiple transcribeSegment calls accumulate in order`() {
        val transcriber = FakeTranscriber(mutableListOf("erster satz", "zweiter satz"))
        val session = TranscriptionSession(transcriber)

        session.transcribeSegment(chunk(100, 0.5f))
        session.transcribeSegment(chunk(100, 0.7f))

        assertEquals(listOf("erster satz", "zweiter satz"), session.segments)
    }

    @Test
    fun `stop resets the transcriber context`() {
        val transcriber = FakeTranscriber(mutableListOf())
        val session = TranscriptionSession(transcriber)

        session.stop()

        assertEquals(1, transcriber.resetContextCalls)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "ai.healthcarepoc.voice.TranscriptionSessionTest"`
Expected: compile failure — `TranscriptionSession` constructor still requires a `PauseDetector`, and has no `transcribeSegment` method yet.

- [ ] **Step 3: Rewrite TranscriptionSession**

Replace `composeApp/src/commonMain/kotlin/ai/healthcarepoc/voice/TranscriptionSession.kt` in full:

```kotlin
package ai.healthcarepoc.voice

interface Transcriber {
    fun transcribe(samples: FloatArray): String

    // Clears any decoding context carried over between transcribe() calls (e.g. prior
    // segment tokens used as a prompt), so the next recording session starts blind
    // instead of being biased by the previous session's tail.
    fun resetContext()
}

// Just the transcribe stage of the pipeline - segmentation (deciding where a segment
// starts/ends) is SpeechSegmenter's job, running in a separate, real-time coroutine so
// it's never coupled to how long transcribe() takes. See docs/superpowers/specs/
// 2026-08-25-vad-segmentation-design.md.
class TranscriptionSession(private val transcriber: Transcriber) {
    private val finalizedSegments = mutableListOf<String>()

    val segments: List<String> get() = finalizedSegments.toList()

    fun transcribeSegment(samples: FloatArray) {
        debugLog("TranscriptionSession.transcribeSegment: starting transcribe() on ${samples.size} samples")
        val startMs = nowMs()
        val text = transcriber.transcribe(samples)
        val elapsedMs = nowMs() - startMs
        debugLog("TranscriptionSession.transcribeSegment: transcribe() returned after ${elapsedMs}ms, textLength=${text.length}, text=\"$text\"")
        finalizedSegments.add(text)
    }

    fun stop() {
        debugLog("TranscriptionSession.stop: resetting transcriber context")
        transcriber.resetContext()
    }
}
```

- [ ] **Step 4: Delete PauseDetector and its test**

```bash
rm composeApp/src/commonMain/kotlin/ai/healthcarepoc/voice/PauseDetector.kt
rm composeApp/src/commonTest/kotlin/ai/healthcarepoc/voice/PauseDetectorTest.kt
```

- [ ] **Step 5: Run the full common test suite to verify everything passes**

Run: `./gradlew :composeApp:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL` — this will also fail to compile at this point because `App.kt` (not yet updated) still references the old `TranscriptionSession(engine, PauseDetector(...))` constructor and `activeSession.acceptAudio(...)`. That's expected and gets fixed in Task 8 — if you're executing tasks in order, note this compile error here, don't try to fix `App.kt` inside this task (its rewrite is Task 8's job so the diff stays reviewable as its own unit). If running Task 7 in isolation for review, verify `SpeechSegmenterTest` and `TranscriptionSessionTest` both pass via their scoped `--tests` filters (Task 6 Step 4 and Task 7 Step 2 commands) rather than the full suite.

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/commonMain/kotlin/ai/healthcarepoc/voice/TranscriptionSession.kt \
        composeApp/src/commonTest/kotlin/ai/healthcarepoc/voice/TranscriptionSessionTest.kt
git rm composeApp/src/commonMain/kotlin/ai/healthcarepoc/voice/PauseDetector.kt \
       composeApp/src/commonTest/kotlin/ai/healthcarepoc/voice/PauseDetectorTest.kt
git commit -m "Simplify TranscriptionSession to just the transcribe stage; delete PauseDetector"
```

(This commit leaves `App.kt` non-compiling until Task 8 — both tasks should land together before pushing/merging, even though they're separate commits for review clarity.)

---

## Task 8: Wire the two-stage pipeline into App.kt

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/ai/healthcarepoc/voice/App.kt`

**Interfaces:**
- Consumes: `WhisperVad` (Task 4/5), `SpeechSegmenter` (Task 6), `TranscriptionSession` (Task 7), `ModelPathProvider.resolveVadModelPath()` (Task 2).

Not unit-testable (no existing test coverage for `App.kt` today). Verified manually on-device, re-running the exact repro used to diagnose the original bug.

- [ ] **Step 1: Add a small pipeline holder and update session construction**

In `composeApp/src/commonMain/kotlin/ai/healthcarepoc/voice/App.kt`, add this private class near the top of the file (after the existing `enum class AsrEngine` / `sealed interface UiState` declarations, before `@Composable fun App`):

```kotlin
// Bundles the two Whisper-path components that must be constructed together (and fail
// together) at session start: the segmenter (VAD-driven, real-time) and the
// transcription session (slow, decoupled via segmentChannel - see startRecording()).
private class WhisperPipeline(val session: TranscriptionSession, val segmenter: SpeechSegmenter)
```

Then replace the existing `session` construction:

```kotlin
    // Loading the model can fail (missing/corrupt bundled file); session stays null
    // and an error state is shown instead of letting the app crash on first use.
    val session = remember {
        runCatching {
            val engine = WhisperEngine(modelPathProvider.resolveModelPath())
            TranscriptionSession(engine, PauseDetector(sampleRateHz = 16_000))
        }.onFailure { e ->
            uiState = UiState.Error(e.message ?: "Failed to load speech model")
        }.getOrNull()
    }
```

with:

```kotlin
    // Loading either model can fail (missing/corrupt bundled file); pipeline stays null
    // and an error state is shown instead of letting the app crash on first use.
    val pipeline = remember {
        runCatching {
            val engine = WhisperEngine(modelPathProvider.resolveModelPath())
            val vad = WhisperVad(modelPathProvider.resolveVadModelPath())
            WhisperPipeline(TranscriptionSession(engine), SpeechSegmenter(vad, sampleRateHz = 16_000))
        }.onFailure { e ->
            uiState = UiState.Error(e.message ?: "Failed to load speech model")
        }.getOrNull()
    }
```

- [ ] **Step 2: Add the second channel and job**

Replace:

```kotlin
    var audioChannel by remember { mutableStateOf<Channel<FloatArray>?>(null) }
    var consumerJob by remember { mutableStateOf<Job?>(null) }
    var nativeStartJob by remember { mutableStateOf<Job?>(null) }
```

with:

```kotlin
    var audioChannel by remember { mutableStateOf<Channel<FloatArray>?>(null) }
    var segmentChannel by remember { mutableStateOf<Channel<FloatArray>?>(null) }
    var segmenterJob by remember { mutableStateOf<Job?>(null) }
    var consumerJob by remember { mutableStateOf<Job?>(null) }
    var nativeStartJob by remember { mutableStateOf<Job?>(null) }
```

- [ ] **Step 3: Rewrite the WHISPER branch of startRecording()**

Replace the `AsrEngine.WHISPER ->` branch inside `startRecording()`:

```kotlin
            AsrEngine.WHISPER -> {
                val activeSession = session ?: return
                uiState = UiState.Recording
                val channel = Channel<FloatArray>(Channel.UNLIMITED)
                audioChannel = channel
                consumerJob = scope.launch {
                    for (samples in channel) {
                        activeSession.acceptAudio(samples)
                        val allSegments = activeSession.segments
                        if (allSegments.size > whisperSegmentsShown) {
                            transcript = transcript + allSegments.subList(whisperSegmentsShown, allSegments.size)
                                .map { "[Whisper] $it" }
                            whisperSegmentsShown = allSegments.size
                        }
                    }
                    debugLog("App: audio consumer loop exiting (channel closed)")
                }
                audioCapture.start { samples ->
                    val resampled = resampleTo16k(samples, nativeSampleRateHz())
                    val result = channel.trySend(resampled)
                    if (result.isFailure) {
                        debugLog("App: audioChannel.trySend failed (channel closed?): $result")
                    }
                }
                debugLog("App.startRecording: audioCapture.start() returned, uiState=Recording")
            }
```

with:

```kotlin
            AsrEngine.WHISPER -> {
                val activePipeline = pipeline ?: return
                uiState = UiState.Recording

                val audio = Channel<FloatArray>(Channel.UNLIMITED)
                audioChannel = audio
                val segments = Channel<FloatArray>(Channel.UNLIMITED)
                segmentChannel = segments

                // Stage 1: real-time segmenter. Only does VAD-driven boundary detection -
                // never calls transcribe() - so it can't fall behind no matter how slow
                // transcription is. This is what fixes segment boundaries being computed
                // against a stale backlog (see the design spec's Evidence section).
                segmenterJob = scope.launch {
                    for (samples in audio) {
                        activePipeline.segmenter.accept(samples).forEach { segment ->
                            val result = segments.trySend(segment)
                            if (result.isFailure) {
                                debugLog("App: segmentChannel.trySend failed (channel closed?): $result")
                            }
                        }
                    }
                    activePipeline.segmenter.flush()?.let { segment ->
                        segments.trySend(segment)
                    }
                    segments.close()
                    debugLog("App: segmenter loop exiting (audioChannel closed)")
                }

                // Stage 2: transcribe consumer. Unchanged in spirit from before - just now
                // fed from segmentChannel (already-finalized segments) instead of raw audio.
                consumerJob = scope.launch {
                    for (segment in segments) {
                        activePipeline.session.transcribeSegment(segment)
                        val allSegments = activePipeline.session.segments
                        if (allSegments.size > whisperSegmentsShown) {
                            transcript = transcript + allSegments.subList(whisperSegmentsShown, allSegments.size)
                                .map { "[Whisper] $it" }
                            whisperSegmentsShown = allSegments.size
                        }
                    }
                    debugLog("App: transcribe consumer loop exiting (segmentChannel closed)")
                }

                audioCapture.start { samples ->
                    val resampled = resampleTo16k(samples, nativeSampleRateHz())
                    val result = audio.trySend(resampled)
                    if (result.isFailure) {
                        debugLog("App: audioChannel.trySend failed (channel closed?): $result")
                    }
                }
                debugLog("App.startRecording: audioCapture.start() returned, uiState=Recording")
            }
```

- [ ] **Step 4: Rewrite the WHISPER branch of stopRecording()**

Replace the `AsrEngine.WHISPER ->` branch inside `stopRecording()`:

```kotlin
            AsrEngine.WHISPER -> {
                val activeSession = session ?: return
                val t0 = nowMs()
                audioCapture.stop()
                debugLog("App.stopRecording: audioCapture.stop() returned after ${nowMs() - t0}ms")
                // Close the channel and wait for the consumer to finish processing everything already
                // queued (including any transcribe() call currently in flight) before force-finalizing
                // the pending buffer - otherwise stop() could race the consumer and finalize a stale or
                // incomplete pending buffer.
                val t1 = nowMs()
                audioChannel?.close()
                consumerJob?.join()
                audioChannel = null
                consumerJob = null
                debugLog("App.stopRecording: audio consumer drained after ${nowMs() - t1}ms")
                val t2 = nowMs()
                val finalSegments = activeSession.stop()
                if (finalSegments.size > whisperSegmentsShown) {
                    transcript = transcript + finalSegments.subList(whisperSegmentsShown, finalSegments.size)
                        .map { "[Whisper] $it" }
                    whisperSegmentsShown = finalSegments.size
                }
                debugLog("App.stopRecording: activeSession.stop() returned after ${nowMs() - t2}ms, segments=${finalSegments.size}")
            }
```

with:

```kotlin
            AsrEngine.WHISPER -> {
                val activePipeline = pipeline ?: return
                val t0 = nowMs()
                audioCapture.stop()
                debugLog("App.stopRecording: audioCapture.stop() returned after ${nowMs() - t0}ms")
                // Drain in pipeline order: audioChannel first (segmenterJob processes whatever was
                // already captured, flushes its trailing buffer, then closes segmentChannel), then
                // segmentChannel (consumerJob transcribes whatever the segmenter produced, including
                // the flushed tail). Each join() only returns once its stage has genuinely finished,
                // so this can't race a still-in-flight transcribe() call the way finalizing a shared
                // buffer directly would.
                val t1 = nowMs()
                audioChannel?.close()
                segmenterJob?.join()
                segmenterJob = null
                audioChannel = null
                consumerJob?.join()
                consumerJob = null
                segmentChannel = null
                debugLog("App.stopRecording: pipeline drained after ${nowMs() - t1}ms")
                val t2 = nowMs()
                activePipeline.session.stop()
                val finalSegments = activePipeline.session.segments
                if (finalSegments.size > whisperSegmentsShown) {
                    transcript = transcript + finalSegments.subList(whisperSegmentsShown, finalSegments.size)
                        .map { "[Whisper] $it" }
                    whisperSegmentsShown = finalSegments.size
                }
                debugLog("App.stopRecording: session.stop() returned after ${nowMs() - t2}ms, segments=${finalSegments.size}")
            }
```

- [ ] **Step 5: Compile**

Run: `./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:compileKotlinIosSimulatorArm64`
Expected: `BUILD SUCCESSFUL`, no remaining references to `session`, `PauseDetector`, or `activeSession.acceptAudio(...)`.

- [ ] **Step 6: Run the full unit test suite**

Run: `./gradlew :composeApp:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, all tests pass (`SpeechSegmenterTest`, `TranscriptionSessionTest`, plus any untouched existing tests).

- [ ] **Step 7: Build the native library, install, and verify on-device**

Run: `./gradlew :composeApp:installDebug`

Repeat the exact repro from the design spec's Evidence section: launch the app, select Whisper, tap Record, speak several sentences with a natural pause partway through, tap Stop. Capture logs with `adb logcat | grep VoiceDebug` as before.

Expected, compared to the pre-VAD behavior documented in the spec:
- No segment text ends with a truncating "..." at a point that doesn't correspond to where you actually paused.
- The "Stopping…" UI state resolves close to real-time after tapping Stop, not 10+ seconds later.
- `App.stopRecording: pipeline drained after ...ms` in the log is small (roughly the time for any single in-flight `transcribe()` call to finish), not the multi-second backlog-drain time seen before.

- [ ] **Step 8: Commit**

```bash
git add composeApp/src/commonMain/kotlin/ai/healthcarepoc/voice/App.kt
git commit -m "Wire real-time VAD segmentation into the Whisper recording pipeline"
```

---

## Plan Self-Review Notes

- **Spec coverage:** Architecture (two-stage pipeline) → Task 8. `VoiceActivityDetector`/`WhisperVad` → Tasks 3–5. `SpeechSegmenter` → Task 6. `TranscriptionSession` simplification → Task 7. Model bundling → Tasks 1–2. "Removed" section (PauseDetector, hallucination guard) → Task 7. Implementation risk (streaming VAD behavior) → Task 4's instrumented test. Testing & Success Criteria → Task 6 (unit), Task 4 (instrumented), Task 8 Step 7 (manual end-to-end).
- **Type consistency check:** `VoiceActivityDetector.segments(minSilenceDurationMs: Int)` signature matches across Task 3 (interface), Task 4/5 (`WhisperVad` actuals), and Task 6 (`FakeVad`, `SpeechSegmenter`'s call site). `SpeechSegmenter` constructor/method names (`accept`, `flush`, `reset`) match between Task 6's definition and Task 8's `App.kt` usage. `TranscriptionSession` methods (`transcribeSegment`, `stop`, `segments`) match between Task 7's definition and Task 8's usage.
