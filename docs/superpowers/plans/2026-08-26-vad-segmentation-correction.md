# VAD Segmentation Correction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the whisper.cpp VAD calling pattern in the already-implemented real-time segmentation pipeline (it currently can't work — the original design assumed segment boundaries could accumulate across `whisper_vad_*` calls, which the API doesn't support), plus two secondary issues a final review found: the VAD model isn't registered in the iOS Xcode project, and native handles are never released.

**Architecture:** No change to the two-stage pipeline (real-time segmenter coroutine → slow transcribe coroutine) from `docs/superpowers/plans/2026-08-25-vad-segmentation.md`. This plan only corrects `VoiceActivityDetector`'s contract (per-chunk speech probability, not accumulated segment boundaries) and `SpeechSegmenter`'s internals (owns trailing-silence bookkeeping itself again, like the old `PauseDetector` did, just VAD-driven) — `App.kt`'s channel/coroutine wiring from the prior plan is untouched except for adding native-handle cleanup.

**Tech Stack:** Kotlin Multiplatform (Android JNI / iOS cinterop), whisper.cpp's `whisper_vad_detect_speech_no_reset`/`whisper_vad_probs`/`whisper_vad_n_probs` (per-chunk classifier use only — `whisper_vad_segments_from_probs` is no longer used).

**Spec:** `docs/superpowers/specs/2026-08-25-vad-segmentation-design.md` — see the "Correction (2026-08-26)" and "Components (corrected, revised after a second independent review)" sections specifically; those supersede the original "Components" section for `VoiceActivityDetector`/`WhisperVad`/`SpeechSegmenter`.

## Global Constraints

- `VoiceActivityDetector.speechProbability()` returns the max probability across a chunk's sub-windows, not an average.
- `SpeechSegmenter` uses hysteresis: enter a speech run at `threshold` (default 0.5), exit only after `minSilenceDurationMs` (default 500) of trailing audio below `negThreshold` (default 0.35) — mirrors whisper.cpp's own internal hysteresis.
- `whisper_vad_segments_from_probs()` and everything downstream of it (`whisper_vad_params`, segment t0/t1) must not appear anywhere in the corrected code — the whole point of this plan is that API can't be used incrementally.
- `SpeechSegmenter`'s public signature (`accept(samples: FloatArray): List<FloatArray>`, `flush(): FloatArray?`, `reset()`) stays exactly as it already is — `App.kt`'s existing call sites (from the prior plan) must not need to change for this reason.

---

## Task 1: Correct VoiceActivityDetector interface and WhisperVad — Android

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/ai/healthcarepoc/voice/VoiceActivityDetector.kt`
- Modify: `composeApp/src/commonMain/kotlin/ai/healthcarepoc/voice/WhisperVad.kt`
- Modify: `composeApp/src/androidMain/kotlin/ai/healthcarepoc/voice/WhisperVad.android.kt`
- Modify: `composeApp/src/androidMain/cpp/whisper_vad_jni.cpp`

**Interfaces:**
- Produces: `VoiceActivityDetector.speechProbability(samples: FloatArray): Float`, `resetState()` — replaces the old `feed()`/`segments(minSilenceDurationMs)`. Consumed by Task 2 (iOS actual) and Task 3 (`SpeechSegmenter` + its test fake).

No unit tests in this task — `WhisperVad`'s native wrapper isn't unit-testable (same situation as `WhisperEngine`); verification is a compile check plus the native library build, consistent with how the original `WhisperVad` addition was verified. `SpeechSegmenter`'s policy logic (which IS unit-tested) is Task 3, against a fake `VoiceActivityDetector` — this task only needs to compile.

- [ ] **Step 1: Rewrite the VoiceActivityDetector interface**

Replace `composeApp/src/commonMain/kotlin/ai/healthcarepoc/voice/VoiceActivityDetector.kt` in full:

```kotlin
package ai.healthcarepoc.voice

// Thin seam over whisper.cpp's VAD so the segmentation policy (SpeechSegmenter) can be
// unit-tested against a fake, without needing the real VAD model. whisper_vad_* does not
// support accumulating a probability trace across calls - each call to
// whisper_vad_detect_speech_no_reset() overwrites the context's probability buffer with
// only that call's chunk (see docs/superpowers/specs/2026-08-25-vad-segmentation-design.md,
// "Correction" section) - so this is a per-chunk classifier, not a segment-boundary deriver.
// The model's recurrent hidden state does carry forward across calls even though the
// probability output doesn't, so results are still informed by everything fed before.
interface VoiceActivityDetector {
    // Speech probability [0, 1] for one capture chunk - the max across the chunk's
    // sub-windows, not an average, so a short loud syllable in an otherwise-quiet chunk
    // isn't diluted away.
    fun speechProbability(samples: FloatArray): Float

    // Clears VAD state between recordings.
    fun resetState()
}
```

- [ ] **Step 2: Update the commonMain expect declaration**

Replace `composeApp/src/commonMain/kotlin/ai/healthcarepoc/voice/WhisperVad.kt` in full:

```kotlin
package ai.healthcarepoc.voice

expect class WhisperVad(modelPath: String) : VoiceActivityDetector {
    override fun speechProbability(samples: FloatArray): Float
    override fun resetState()
    fun release()
}
```

- [ ] **Step 3: Rewrite the VAD JNI source**

Replace `composeApp/src/androidMain/cpp/whisper_vad_jni.cpp` in full:

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
```

Note: `nativeSegments` is gone entirely — `whisper_vad_segments_from_probs`/`whisper_vad_params` are no longer called anywhere in this file. `CMakeLists.txt` doesn't need any change (still just `whisper_jni.cpp whisper_vad_jni.cpp` in the same target).

- [ ] **Step 4: Rewrite the Android actual**

Replace `composeApp/src/androidMain/kotlin/ai/healthcarepoc/voice/WhisperVad.android.kt` in full:

```kotlin
package ai.healthcarepoc.voice

actual class WhisperVad actual constructor(modelPath: String) : VoiceActivityDetector {
    private val handle: Long = run {
        debugLog("WhisperVad.<init>: calling nativeInit, modelPath=$modelPath")
        val result = nativeInit(modelPath)
        check(result != 0L) { "Failed to load VAD model at $modelPath" }
        result
    }

    actual override fun speechProbability(samples: FloatArray): Float {
        return nativeSpeechProbability(handle, samples)
    }

    actual override fun resetState() {
        nativeResetState(handle)
    }

    actual fun release() {
        nativeRelease(handle)
    }

    private external fun nativeInit(modelPath: String): Long
    private external fun nativeSpeechProbability(handle: Long, samples: FloatArray): Float
    private external fun nativeResetState(handle: Long)
    private external fun nativeRelease(handle: Long)

    companion object {
        init {
            System.loadLibrary("whisper_jni")
        }
    }
}
```

- [ ] **Step 5: Build the native library and compile**

Run: `./gradlew :composeApp:externalNativeBuildDebug :composeApp:compileDebugKotlinAndroid`
Expected: `BUILD SUCCESSFUL`. This will fail at this point if `SpeechSegmenter.kt`/`WhisperVad.ios.kt` (not yet updated — Tasks 2-3) reference the old interface; if so, that's expected, not a defect of this task — those are separate tasks. To verify Task 1 in isolation, it's enough that `whisper_vad_jni.cpp` compiles as part of the native build and that `WhisperVad.android.kt`/`WhisperVad.kt`/`VoiceActivityDetector.kt` have no *internal* inconsistency (matching signatures against each other) even if other not-yet-updated files elsewhere in the module fail.

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/commonMain/kotlin/ai/healthcarepoc/voice/VoiceActivityDetector.kt \
        composeApp/src/commonMain/kotlin/ai/healthcarepoc/voice/WhisperVad.kt \
        composeApp/src/androidMain/kotlin/ai/healthcarepoc/voice/WhisperVad.android.kt \
        composeApp/src/androidMain/cpp/whisper_vad_jni.cpp
git commit -m "Correct VoiceActivityDetector/WhisperVad to a per-chunk classifier (Android)"
```

---

## Task 2: Correct WhisperVad — iOS

**Files:**
- Modify: `composeApp/src/iosMain/kotlin/ai/healthcarepoc/voice/WhisperVad.ios.kt`

**Interfaces:**
- Consumes: `VoiceActivityDetector` (Task 1's corrected version), `expect class WhisperVad` (Task 1, Step 2).

No iOS on-device/instrumented test infra exists in this repo — compile-check only, same as the original `WhisperVad.ios.kt` addition.

- [ ] **Step 1: Rewrite the iOS actual**

Replace `composeApp/src/iosMain/kotlin/ai/healthcarepoc/voice/WhisperVad.ios.kt` in full:

```kotlin
package ai.healthcarepoc.voice

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.readValue
import kotlinx.cinterop.usePinned
import platform.Foundation.NSProcessInfo
import whispercinterop.whisper_vad_context_params
import whispercinterop.whisper_vad_default_context_params
import whispercinterop.whisper_vad_detect_speech_no_reset
import whispercinterop.whisper_vad_free
import whispercinterop.whisper_vad_init_from_file_with_params
import whispercinterop.whisper_vad_n_probs
import whispercinterop.whisper_vad_probs
import whispercinterop.whisper_vad_reset_state
import kotlin.math.max

@OptIn(ExperimentalForeignApi::class)
actual class WhisperVad actual constructor(modelPath: String) : VoiceActivityDetector {

    private val vctx = memScoped {
        val cparams = whisper_vad_default_context_params().getPointer(this).pointed
        cparams.n_threads = max(1, NSProcessInfo.processInfo.activeProcessorCount.toInt())
        whisper_vad_init_from_file_with_params(modelPath, cparams.readValue())
            ?: error("Failed to load VAD model at $modelPath")
    }

    // Classifies exactly the given chunk as speech/non-speech. whisper_vad_detect_speech_no_reset
    // overwrites the VAD context's probability buffer with only this call's chunk each time - it
    // does NOT accumulate across calls. Only the model's recurrent hidden state carries forward.
    // So the probabilities must be read back immediately, before the next call overwrites them.
    actual override fun speechProbability(samples: FloatArray): Float {
        val detected = samples.usePinned { pinned ->
            whisper_vad_detect_speech_no_reset(vctx, pinned.addressOf(0), samples.size)
        }
        if (!detected) return 0.0f

        val nProbs = whisper_vad_n_probs(vctx)
        val probs = whisper_vad_probs(vctx) ?: return 0.0f
        var maxProb = 0.0f
        for (i in 0 until nProbs) {
            maxProb = max(maxProb, probs[i])
        }
        return maxProb
    }

    actual override fun resetState() {
        whisper_vad_reset_state(vctx)
    }

    actual fun release() {
        whisper_vad_free(vctx)
    }
}
```

Note the import list drops everything `whisper_vad_segments_*`/`whisper_vad_default_params`/`whisper_vad_free_segments`/`whisper_vad_segments_from_probs`/`whisper_vad_segments_get_segment_t0/t1`/`whisper_vad_segments_n_segments` related — none of that API is used anymore. `whisper_vad_probs(vctx)` returns a nullable `CPointer<FloatVar>?` in the cinterop binding; index it with `kotlinx.cinterop.get` (imported above) via `probs[i]`, matching how indexed `IntVar`/`FloatVar` access is done elsewhere in this codebase's iOS cinterop code.

- [ ] **Step 2: Compile**

Run: `./gradlew :composeApp:compileKotlinIosSimulatorArm64`
Expected: `BUILD SUCCESSFUL`, no unresolved-reference errors. This will also fail if `SpeechSegmenter.kt` hasn't been updated yet (Task 3) — expected if running this task before Task 3; to check this task in isolation, confirm any remaining compile errors are only in `SpeechSegmenter.kt`/`SpeechSegmenterTest.kt`, not in `WhisperVad.ios.kt` itself.

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/iosMain/kotlin/ai/healthcarepoc/voice/WhisperVad.ios.kt
git commit -m "Correct WhisperVad to a per-chunk classifier (iOS)"
```

---

## Task 3: Correct SpeechSegmenter to own trailing-silence bookkeeping

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/ai/healthcarepoc/voice/SpeechSegmenter.kt`
- Modify: `composeApp/src/commonTest/kotlin/ai/healthcarepoc/voice/SpeechSegmenterTest.kt`

**Interfaces:**
- Consumes: `VoiceActivityDetector.speechProbability()`/`resetState()` (Task 1).
- Produces: `SpeechSegmenter(vad, sampleRateHz, minSilenceDurationMs = 500, threshold = 0.5f, negThreshold = 0.35f)` with `accept(samples: FloatArray): List<FloatArray>`, `flush(): FloatArray?`, `reset()` — **same public signature as before this plan**, so `App.kt` (Task 4, and the prior plan's wiring) doesn't need to change its call sites.

Full TDD — this is the core, fully unit-testable policy logic (same as before), now against a `FakeVad` returning canned probabilities instead of canned segment lists.

- [ ] **Step 1: Write the failing tests**

Replace `composeApp/src/commonTest/kotlin/ai/healthcarepoc/voice/SpeechSegmenterTest.kt` in full:

```kotlin
package ai.healthcarepoc.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private class FakeVad(private val probsPerCall: MutableList<Float>) : VoiceActivityDetector {
    var resetCalls = 0

    override fun speechProbability(samples: FloatArray): Float =
        if (probsPerCall.isNotEmpty()) probsPerCall.removeAt(0) else 0.0f

    override fun resetState() {
        resetCalls++
    }
}

class SpeechSegmenterTest {

    private val sampleRate = 16_000

    // 100ms chunks @ 16kHz = 1600 samples, matching AudioCapture's real chunk size.
    private fun chunk(value: Float): FloatArray = FloatArray(1600) { value }

    @Test
    fun `accept returns nothing while probability stays below threshold`() {
        val vad = FakeVad(mutableListOf(0.1f, 0.1f))
        val segmenter = SpeechSegmenter(vad, sampleRate, minSilenceDurationMs = 700)

        assertEquals(emptyList(), segmenter.accept(chunk(0.5f)))
        assertEquals(emptyList(), segmenter.accept(chunk(0.5f)))
    }

    @Test
    fun `finalizes a segment after speech then enough trailing low-probability chunks`() {
        val vad = FakeVad((mutableListOf(0.9f) + List(7) { 0.1f }).toMutableList())
        val segmenter = SpeechSegmenter(vad, sampleRate, minSilenceDurationMs = 700)

        segmenter.accept(chunk(1.0f))
        var result: List<FloatArray> = emptyList()
        repeat(7) { result = segmenter.accept(chunk(0.0f)) }

        assertEquals(1, result.size)
    }

    @Test
    fun `drops a silent buffer that never had speech, without emitting it`() {
        val vad = FakeVad(MutableList(7) { 0.1f })
        val segmenter = SpeechSegmenter(vad, sampleRate, minSilenceDurationMs = 700)

        var sawSegment = false
        repeat(7) { if (segmenter.accept(chunk(0.0f)).isNotEmpty()) sawSegment = true }

        assertEquals(false, sawSegment)
        assertNull(segmenter.flush())
    }

    @Test
    fun `stays in speech through an ambiguous chunk between negThreshold and threshold`() {
        // 0.9 enters speech; 0.4 is between negThreshold(0.35) and threshold(0.5) - must NOT
        // count as silence and must NOT exit speech; trailing silence still needs the full
        // duration counted only from genuinely low-probability chunks afterward.
        val vad = FakeVad((mutableListOf(0.9f, 0.4f) + List(7) { 0.1f }).toMutableList())
        val segmenter = SpeechSegmenter(vad, sampleRate, minSilenceDurationMs = 700)

        segmenter.accept(chunk(1.0f)) // 0.9 -> enters speech
        val ambiguous = segmenter.accept(chunk(1.0f)) // 0.4 -> ambiguous, stays in speech
        assertEquals(emptyList(), ambiguous)

        var result: List<FloatArray> = emptyList()
        repeat(7) { result = segmenter.accept(chunk(0.0f)) }
        assertEquals(1, result.size)
    }

    @Test
    fun `flush returns the pending buffer if it had speech`() {
        val vad = FakeVad(mutableListOf(0.9f))
        val segmenter = SpeechSegmenter(vad, sampleRate, minSilenceDurationMs = 700)
        val speechChunk = chunk(1.0f)

        segmenter.accept(speechChunk)
        val tail = segmenter.flush()

        assertEquals(speechChunk.toList(), tail!!.toList())
    }

    @Test
    fun `flush returns null when nothing pending had speech`() {
        val vad = FakeVad(mutableListOf(0.1f))
        val segmenter = SpeechSegmenter(vad, sampleRate, minSilenceDurationMs = 700)

        segmenter.accept(chunk(0.0f))

        assertNull(segmenter.flush())
    }

    @Test
    fun `reset clears state and calls vad resetState`() {
        val vad = FakeVad(mutableListOf(0.9f))
        val segmenter = SpeechSegmenter(vad, sampleRate, minSilenceDurationMs = 700)
        segmenter.accept(chunk(1.0f))

        segmenter.reset()

        assertEquals(1, vad.resetCalls)
        assertNull(segmenter.flush())
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "ai.healthcarepoc.voice.SpeechSegmenterTest"`
Expected: compile failure — the existing `SpeechSegmenter`/`VoiceActivityDetector` (before this task's Step 3) still use `feed()`/`segments()`, not `speechProbability()`, so `FakeVad` won't satisfy the interface and `SpeechSegmenter`'s constructor won't accept `threshold`/`negThreshold` yet.

- [ ] **Step 3: Rewrite SpeechSegmenter**

Replace `composeApp/src/commonMain/kotlin/ai/healthcarepoc/voice/SpeechSegmenter.kt` in full:

```kotlin
package ai.healthcarepoc.voice

// Real-time VAD-driven replacement for PauseDetector: decides segment boundaries as audio
// arrives, using whisper.cpp's Silero VAD as a per-chunk speech classifier (see
// VoiceActivityDetector) instead of a static RMS threshold. Mirrors whisper.cpp's own
// hysteresis - entering a speech run requires crossing `threshold`; once in one, only
// trailing audio below the lower `negThreshold` counts toward silence - to avoid boundary
// chatter right at the edge. See docs/superpowers/specs/2026-08-25-vad-segmentation-design.md.
class SpeechSegmenter(
    private val vad: VoiceActivityDetector,
    private val sampleRateHz: Int,
    private val minSilenceDurationMs: Int = 500,
    private val threshold: Float = 0.5f,
    private val negThreshold: Float = 0.35f
) {
    private val pendingSamples = mutableListOf<Float>()
    private var inSpeech = false
    private var trailingSilenceMs = 0

    // Whether any chunk in the current pending buffer crossed `threshold`. A silence period
    // that never had real speech in it is dropped instead of finalized, so the Transcriber
    // stage never sees a purely-silent buffer (avoids Whisper hallucinating text for dead air).
    private var hadSpeech = false

    // Feed one capture chunk. Returns a finalized segment if a speech run just ended
    // (minSilenceDurationMs of trailing below-negThreshold audio), else empty.
    fun accept(samples: FloatArray): List<FloatArray> {
        pendingSamples.addAll(samples.toList())
        val prob = vad.speechProbability(samples)
        val chunkDurationMs = (samples.size * 1000) / sampleRateHz

        if (prob >= threshold) {
            inSpeech = true
            hadSpeech = true
            trailingSilenceMs = 0
        } else if (prob < negThreshold) {
            trailingSilenceMs += chunkDurationMs
        }
        // between negThreshold and threshold: ambiguous chunk, leave trailingSilenceMs as-is

        if (inSpeech && trailingSilenceMs >= minSilenceDurationMs) {
            return finalize()
        }
        if (!inSpeech && trailingSilenceMs >= minSilenceDurationMs) {
            // Long silence before any speech started - drop it so pendingSamples doesn't
            // grow unboundedly while nothing is being said.
            pendingSamples.clear()
            trailingSilenceMs = 0
        }
        return emptyList()
    }

    // Force-finalizes whatever's pending (end of recording, no pause reached). Returns
    // null if there's no pending speech.
    fun flush(): FloatArray? {
        if (!hadSpeech) return null
        return finalize().firstOrNull()
    }

    // Clears state between recordings.
    fun reset() {
        pendingSamples.clear()
        inSpeech = false
        trailingSilenceMs = 0
        hadSpeech = false
        vad.resetState()
    }

    private fun finalize(): List<FloatArray> {
        inSpeech = false
        trailingSilenceMs = 0
        if (!hadSpeech) {
            pendingSamples.clear()
            return emptyList()
        }
        val segment = pendingSamples.toFloatArray()
        pendingSamples.clear()
        hadSpeech = false
        return listOf(segment)
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :composeApp:testDebugUnitTest --tests "ai.healthcarepoc.voice.SpeechSegmenterTest"`
Expected: `BUILD SUCCESSFUL`, all 7 tests pass.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/ai/healthcarepoc/voice/SpeechSegmenter.kt \
        composeApp/src/commonTest/kotlin/ai/healthcarepoc/voice/SpeechSegmenterTest.kt
git commit -m "Correct SpeechSegmenter to own trailing-silence bookkeeping against VAD probability"
```

---

## Task 4: Release native handles from App.kt

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/ai/healthcarepoc/voice/App.kt`

**Interfaces:**
- Consumes: `WhisperEngine.release()`, `WhisperVad.release()` (both already exist, previously only called from instrumented tests).

Not unit-testable (no existing test coverage for `App.kt`). Verified by compiling and confirming the new `DisposableEffect` is wired correctly by inspection/compile — full behavioral verification happens as part of Task 6's on-device pass (native handles being released doesn't have an observable effect from a single manual recording session; this is a leak fix that manifests over many Activity recreations, not something a single manual test run can positively confirm either way).

- [ ] **Step 1: Make WhisperPipeline retain the native handles and add a release() method**

In `composeApp/src/commonMain/kotlin/ai/healthcarepoc/voice/App.kt`, replace:

```kotlin
private class WhisperPipeline(val session: TranscriptionSession, val segmenter: SpeechSegmenter)
```

with:

```kotlin
// Neither TranscriptionSession nor SpeechSegmenter exposes the native WhisperEngine/WhisperVad
// handle it wraps, so WhisperPipeline retains them directly - purely so release() has
// something to call. Nothing else here reaches into them.
private class WhisperPipeline(
    val session: TranscriptionSession,
    val segmenter: SpeechSegmenter,
    private val engine: WhisperEngine,
    private val vad: WhisperVad
) {
    fun release() {
        engine.release()
        vad.release()
    }
}
```

- [ ] **Step 2: Pass the handles through at construction**

Replace:

```kotlin
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

with:

```kotlin
    val pipeline = remember {
        runCatching {
            val engine = WhisperEngine(modelPathProvider.resolveModelPath())
            val vad = WhisperVad(modelPathProvider.resolveVadModelPath())
            WhisperPipeline(TranscriptionSession(engine), SpeechSegmenter(vad, sampleRateHz = 16_000), engine, vad)
        }.onFailure { e ->
            uiState = UiState.Error(e.message ?: "Failed to load speech model")
        }.getOrNull()
    }
    DisposableEffect(pipeline) {
        onDispose { pipeline?.release() }
    }
```

(`DisposableEffect` is already imported in this file — it's used later for `lifecycleObserver`.)

- [ ] **Step 3: Compile**

Run: `./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:compileKotlinIosSimulatorArm64`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Run the full unit test suite**

Run: `./gradlew :composeApp:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, all tests pass (this exercises `SpeechSegmenterTest`/`TranscriptionSessionTest`, not `App.kt` itself, but confirms nothing else broke).

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/ai/healthcarepoc/voice/App.kt
git commit -m "Release WhisperEngine/WhisperVad native handles when the pipeline is disposed"
```

---

## Task 5: Register the VAD model in the iOS Xcode project

**Files:**
- Modify: `iosApp/iosApp.xcodeproj/project.pbxproj`

**Interfaces:**
- None (project file only) — makes `ModelPathProvider.ios.kt`'s existing `resolveBundleResource("ggml-silero-v6.2.0")` (already committed, from the original plan's Task 2) actually resolve at runtime, since Xcode only bundles resources it's told about, not everything that happens to exist on disk under a referenced folder.

Not unit-testable — this is Xcode project metadata. Verified by confirming the four new entries mirror `ggml-small.bin`'s existing ones exactly (same shape, different file), and — if Xcode/xcodebuild is available in the execution environment — an actual build. If Xcode isn't available, a careful manual diff against the `ggml-small.bin` pattern is the fallback verification; note explicitly in the report which was done.

- [ ] **Step 1: Add the PBXBuildFile entry**

In `iosApp/iosApp.xcodeproj/project.pbxproj`, in the `/* Begin PBXBuildFile section */` block, add a new line immediately after the existing `ggml-small.bin` one:

```
		AEB1D432CE2EB1F3141056AB /* ggml-small.bin in Resources */ = {isa = PBXBuildFile; fileRef = 74A54166EC30340FDD381283 /* ggml-small.bin */; };
		19DEA6E900AE45F0B5C75939 /* ggml-silero-v6.2.0.bin in Resources */ = {isa = PBXBuildFile; fileRef = D9887132175E4261A02D6681 /* ggml-silero-v6.2.0.bin */; };
```

(Both new IDs, `19DEA6E900AE45F0B5C75939` and `D9887132175E4261A02D6681`, were generated fresh and confirmed to not collide with any existing ID in this file — use them exactly as given, don't regenerate.)

- [ ] **Step 2: Add the PBXFileReference entry**

In the `/* Begin PBXFileReference section */` block, add a new line immediately after the existing `ggml-small.bin` one:

```
		74A54166EC30340FDD381283 /* ggml-small.bin */ = {isa = PBXFileReference; lastKnownFileType = file; path = "Resources/ggml-small.bin"; sourceTree = "<group>"; };
		D9887132175E4261A02D6681 /* ggml-silero-v6.2.0.bin */ = {isa = PBXFileReference; lastKnownFileType = file; path = "Resources/ggml-silero-v6.2.0.bin"; sourceTree = "<group>"; };
```

- [ ] **Step 3: Add it to the iosApp group's children**

In the `713926576F58A4DCD8F8B7C2 /* iosApp */` `PBXGroup`'s `children` list, add a line immediately after the existing `ggml-small.bin` reference:

```
				74A54166EC30340FDD381283 /* ggml-small.bin */,
				D9887132175E4261A02D6681 /* ggml-silero-v6.2.0.bin */,
```

- [ ] **Step 4: Add it to the Resources build phase**

In the `54C00F963B2EAE62A9D5B8DC /* Resources */` `PBXResourcesBuildPhase`'s `files` list, add a line immediately after the existing `ggml-small.bin` entry:

```
				AEB1D432CE2EB1F3141056AB /* ggml-small.bin in Resources */,
				19DEA6E900AE45F0B5C75939 /* ggml-silero-v6.2.0.bin in Resources */,
```

- [ ] **Step 5: Verify the file is well-formed**

Run: `plutil -lint iosApp/iosApp.xcodeproj/project.pbxproj`
Expected: `iosApp/iosApp.xcodeproj/project.pbxproj: OK` (this validates the property-list syntax is intact after the manual edits — it does not validate Xcode-specific semantics, but catches a broken/malformed file, which is the main risk of hand-editing this format).

If Xcode command-line tools are available in this environment, also run:
`xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -sdk iphonesimulator build 2>&1 | tail -30`
and confirm no "file not found"/resource errors relating to `ggml-silero-v6.2.0.bin`. If `xcodebuild`/a full iOS toolchain isn't available in this environment, state that explicitly in the report rather than skipping silently — `plutil -lint` is the minimum bar, a full build is the preferred one.

- [ ] **Step 6: Commit**

```bash
git add iosApp/iosApp.xcodeproj/project.pbxproj
git commit -m "Register the VAD model as an iOS Xcode Resources build phase entry"
```

## Plan Self-Review Notes

- **Spec coverage:** "Components (corrected, revised after a second independent review)" section → Tasks 1-3. The two secondary findings ("Also found during final review") → Tasks 4-5. Global Constraints (hysteresis values, no `segments_from_probs` anywhere, unchanged `SpeechSegmenter` public signature) → verified inline in each task's code.
- **Type consistency check:** `VoiceActivityDetector.speechProbability(samples: FloatArray): Float` matches across Task 1 (interface + both actuals) and Task 3 (`FakeVad`, `SpeechSegmenter`'s call site). `SpeechSegmenter`'s public signature (`accept`, `flush`, `reset`) is unchanged from the prior plan, so Task 4/`App.kt`'s existing call sites (untouched by this plan except for the `WhisperPipeline`/`DisposableEffect` addition in Task 4) don't need updates. `WhisperPipeline`'s constructor arity change (Task 4) is self-contained to `App.kt`, the only file that constructs it.
- **Ambiguity check:** Task 5's pbxproj edits give exact byte-for-byte lines to add, generated/verified-unique IDs rather than placeholders, and an explicit fallback verification path if a full Xcode toolchain isn't available in the execution environment.
