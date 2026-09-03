# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A Kotlin Multiplatform (Android + iOS) proof of concept that transcribes German
speech **fully on-device** via a vendored `whisper.cpp`. No network call exists
anywhere in the app — this is a deliberate constraint (see README.md "Why
on-device"), not an oversight, so don't introduce one (a remote API, analytics,
crash reporting, etc.) without flagging it explicitly.

## Setup (required before building)

The Whisper model and VAD model are gitignored binaries, not checked into git.
A fresh checkout has no way to build/run until these run once:

```bash
git submodule update --init   # if not cloned with --recurse-submodules
./scripts/download-model.sh       # downloads + q8_0-quantizes ggml-small.bin (~252 MiB)
./scripts/download-vad-model.sh   # downloads the Silero VAD model (a few MB)
```

`download-model.sh` builds `whisper-quantize` from the vendored `third_party/whisper.cpp`
submodule and needs `cmake` on `PATH` (or `CMAKE=/path/to/cmake` if it's only bundled
with the Android SDK, e.g. `$ANDROID_HOME/cmake/<version>/bin/cmake`).

The "Clinical" engine toggle needs a third asset, `ggml-small-clinical-de.bin`, that
`download-model.sh` does *not* produce. It's a clinical-context fine-tune of `ggml-small`
converted from a Hugging Face safetensors checkpoint built by the separate
`medical-data-sources` project. It's optional — the app builds and runs without it, only
the Clinical toggle fails when selected. To enable it:

```bash
cp -r <medical-data-sources>/data/finetune/whisper-small-clinical-de models-src/   # gitignored
./scripts/build-clinical-model.sh   # convert-h5-to-ggml.py -> ggml -> q8_0, same as download-model.sh
```

`build-clinical-model.sh` needs `python3` with `torch` + `transformers` (matching the
checkpoint's `config.json` `transformers_version`) for the ggml conversion, and the same
`cmake` + toolchain as `download-model.sh` for the q8_0 step — without the latter it ships
the unquantized fp16 model (~465 MiB) and prints a warning.

For iOS, whisper.cpp's static libs also aren't built by Gradle — build them once (and
again whenever the `third_party/whisper.cpp` submodule commit changes):

```bash
./scripts/build-whisper-ios.sh
```

## Commands

**Android build/install:**
```bash
./gradlew :composeApp:assembleDebug
./gradlew :composeApp:installDebug   # requires a connected device/emulator
```
First build compiles whisper.cpp from source via CMake/NDK (a few minutes); incremental after.

**Unit tests** (pure-Kotlin, commonTest — `SpeechSegmenter`/`TranscriptionSession`):
```bash
./gradlew :composeApp:testDebugUnitTest
./gradlew :composeApp:testDebugUnitTest --tests "ai.healthcarepoc.voice.SpeechSegmenterTest"
./gradlew :composeApp:testDebugUnitTest --tests "*SpeechSegmenterTest.forces a finalize*"
```

**Android instrumented tests** (on-device, exercises real `WhisperEngine`/`WhisperVad` JNI —
requires a connected device/emulator with the models already pushed via the app's assets):
```bash
./gradlew :composeApp:connectedDebugAndroidTest
```

**iOS:**
```bash
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64   # Kotlin/Gradle only
open iosApp/iosApp.xcodeproj                                # or build/run via Xcode
```
Only `iosArm64` (device) and `iosSimulatorArm64` (Apple Silicon simulator) are supported —
`iosX64` (Intel simulator) is intentionally not built.

There is no configured linter (no ktlint/detekt) and no separate lint Gradle task.

## Architecture

### The three-stage recording pipeline, and why it's decoupled

`AudioCapture → SpeechSegmenter (VAD) → TranscriptionSession (WhisperEngine)`, wired
together in `App.kt`'s `startRecording()`/`stopRecording()`. This is not a straight
pipe — it's two coroutines connected by unlimited `Channel`s, and that decoupling is
load-bearing, not incidental:

- `WhisperEngine.transcribe()` is a **synchronous, blocking native call** that can take
  seconds. If audio capture called it directly, the OS audio buffer overflows and
  speech is silently dropped while a transcribe is in flight.
- The segmenter stage (`segmenterJob`) only does real-time VAD bookkeeping and never
  calls `transcribe()`, so it can never fall behind live audio no matter how slow
  transcription is.
- The transcribe stage (`consumerJob`) runs on its own dedicated single-thread
  dispatcher (`transcribeDispatcher`), not the shared `Dispatchers.Default` scope —
  sharing would let a long `transcribe()` call starve the segmenter's coroutine out of
  the same thread pool, reintroducing the exact problem the two-stage split exists to
  avoid.

When touching this pipeline, preserve the ordering in `stopRecording()`: close
`audioChannel` → join `segmenterJob` (flushes its trailing buffer, closes
`segmentChannel`) → join `consumerJob`. Draining out of order can race an in-flight
`transcribe()` call.

### Platform split: one native library, two bridges

`WhisperEngine` and `WhisperVad` are `expect`/`actual`, each binding the **same**
vendored `third_party/whisper.cpp` C library from a different direction:

- Android: JNI, via `composeApp/src/androidMain/cpp/{whisper_jni,whisper_vad_jni}.cpp`,
  built by the CMake config in that same directory.
- iOS: Kotlin/Native cinterop, via `src/nativeInterop/cinterop/whisper.def` and the
  static libs `build-whisper-ios.sh` produces.

Decoding parameters (`beam_search.beam_size`, `language = "de"`, `n_threads`,
`max_tokens`, dynamic `audio_ctx` sizing) are **duplicated in both bridges** — there is
no shared config, so a tuning change (e.g. beam size) must be made in both
`whisper_jni.cpp` and `WhisperEngine.ios.kt` or the platforms silently diverge.

`AudioCapture` is the other `expect`/`actual` split (Android `AudioRecord`, iOS
`AVAudioEngine`), unrelated to Whisper. Everything else lives in `commonMain`.

### VAD hysteresis tuning lives in `SpeechSegmenter`'s constructor defaults

`threshold` (0.5, enter speech), `negThreshold` (0.15, count toward trailing silence),
`minSilenceDurationMs` (500), and `maxSegmentDurationMs` (30_000, a hard cap independent
of the hysteresis, for when ambient noise sits in the ambiguous band between the two
thresholds indefinitely) were arrived at from on-device regression testing, not derived
analytically — see `docs/superpowers/specs/2026-08-25-vad-segmentation-design.md`. Unit
tests in `SpeechSegmenterTest` cover the state machine's logic with a `FakeVad`, but
don't validate the threshold *values* against real speech — changes to these constants
need on-device verification, not just green tests.

### On-screen debug/metrics logging

`debugLog()` (`DebugLog.kt`) prefixes `[VoiceDebug]` and goes to stdout/Logcat; it's used
throughout the pipeline for timing and state tracing. Separately, `App.kt` appends a
per-recording metrics summary (end-to-end time, time-to-first-segment, per-segment
real-time-factor, segment-channel backlog) directly into the on-screen transcript as a
`TranscriptEntry.Metrics` entry, since Logcat isn't always reachable while testing on a
device — this is intentionally reachable from the UI, not just diagnostic noise, though
it's hidden by default and only revealed by tapping the recording it belongs to (see
`groupByRecording()`'s per-recording `clickable` in `App.kt`).

### Engine comparison toggle

`AsrEngine` in `App.kt` has three values, A/B'd by ear via a toggle next to the Record
button:

- `WHISPER` and `WHISPER_CLINICAL` run the **exact same** pipeline (`startRecording()`/
  `stopRecording()`/metrics all use `AsrEngine.WHISPER, AsrEngine.WHISPER_CLINICAL ->`
  as one branch). They differ only in which file `WhisperEngine` loads — stock
  `ggml-small.bin` vs the fine-tune `ggml-small-clinical-de.bin`
  (`ModelPathProvider.resolveModelPath()` vs `resolveClinicalModelPath()`). Decoding
  params in `whisper_jni.cpp` / `WhisperEngine.ios.kt` are unchanged and apply to both.
- `NATIVE` (Android only) is Android's on-device `SpeechRecognizer`
  (`AndroidSpeechRecognizerEngine`), gated on `isOnDeviceRecognitionAvailable` (API 31+).
  See `docs/superpowers/specs/2026-08-24-native-asr-comparison-design.md`.

Only **one** Whisper model is resident at a time. `App.kt` tracks `useClinicalModel`
(set only by the Whisper/Clinical toggle handlers — switching to/from `NATIVE` leaves it
alone, so the model stays loaded) and a `LaunchedEffect(useClinicalModel)` rebuilds the
`pipeline` on `transcribeDispatcher` whenever it flips: release the old engine/VAD, null
`pipeline` (UI shows "Loading model…", toggles + Record disabled), load the new one
(~1–3 s). The toggle is `uiState == Idle && pipeline != null`-gated, so a model swap can
never race an in-flight recording or a still-loading model. `scope` + `transcribeDispatcher`
teardown moved to a `DisposableEffect(Unit)` (they outlive any single model); the
`LaunchedEffect` owns releasing the outgoing model on a swap.

The toggle row renders on iOS too (Whisper/Clinical only — `nativeAsr` is a nullable
`App()` parameter, always null there).

## Design docs

`docs/superpowers/specs/` and `docs/superpowers/plans/` hold the design rationale and
implementation plans behind the major features (initial on-device transcription
architecture, VAD segmentation and its hysteresis tuning, the native-ASR comparison
toggle). Check these before making architectural changes in areas they cover.
