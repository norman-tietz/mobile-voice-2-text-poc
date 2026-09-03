# German Voice Transcription PoC

A Kotlin Multiplatform (Android + iOS) proof of concept that transcribes
German speech **fully on-device** using [whisper.cpp](https://github.com/ggml-org/whisper.cpp).
No audio or transcript data ever leaves the device — there is no network
call anywhere in the app, and no cloud speech-to-text vendor involved.

While recording, the transcript grows in segments: each pause in speech
finalizes and transcribes the audio spoken since the last pause, so text
appears incrementally rather than only after you stop recording.

## Why on-device

This PoC exists to prove that on-device German speech-to-text is viable for
a context (clinical/healthcare dictation) where audio and transcript content
must never be accessible to a third party. Platform-native recognizers
(`SFSpeechRecognizer`, Android `SpeechRecognizer`) were ruled out because
they default to server-side recognition, forcing on-device mode isn't
uniformly available/guaranteed across OS versions and OEMs, and the
recognition code itself is closed-source. A bundled, open-source model that
the app runs itself gives an auditable compliance story and consistent
behavior on both platforms. See
`docs/superpowers/specs/2026-08-03-german-streaming-voice-transcription-design.md`
for the full design rationale, including why
[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) was tried first and
reverted in favor of whisper.cpp.

## How it works

```
AudioCapture (mic) → resampleTo16k → audioChannel
                                          ↓
                     SpeechSegmenter (WhisperVad) — real-time, never calls transcribe()
                                          ↓
                                    segmentChannel
                                          ↓
            TranscriptionSession → WhisperEngine (whisper.cpp) — own dispatcher thread
                                          ↓
                              Compose UI (transcript segments)
```

- **`AudioCapture`** (`expect`/`actual`) — captures microphone audio.
  Android via `AudioRecord` (with `NoiseSuppressor` attached where the
  device supports it), iOS via `AVAudioEngine`.
- **`SpeechSegmenter`** — VAD-probability-driven trailing-silence detection;
  feeds each audio chunk to `WhisperVad` (a thin wrapper over whisper.cpp's
  Silero VAD, `expect`/`actual`) for a per-chunk speech probability, and
  applies hysteresis bookkeeping (entering speech requires crossing a high
  threshold, leaving it requires ~500ms of trailing audio below a lower
  threshold) to decide segment boundaries in real time, plus a 30s hard cap
  on a single segment regardless of hysteresis, for sustained ambient noise
  that never resolves clearly to silence.
- Capture and segmentation are decoupled from transcription by two
  unlimited `Channel`s (`audioChannel`, `segmentChannel`) and run as
  separate coroutines — `SpeechSegmenter` only ever does real-time VAD
  bookkeeping, so it can't fall behind no matter how long a
  `transcribe()` call takes. The transcribe stage runs on its own
  dedicated single-thread dispatcher rather than the shared coroutine
  pool, so a long native decode can't starve the segmenter out of CPU
  time either.
- **`TranscriptionSession`** — receives already-finalized segments off
  `segmentChannel` and triggers transcription of each one, or of whatever
  is still pending when recording stops.
- **`WhisperEngine`** (`expect`/`actual`) — runs whisper.cpp natively.
  Android via a JNI/CMake binding, iOS via Kotlin/Native cinterop. Both
  bind the same vendored `whisper.cpp` C library and the same bundled
  German multilingual `ggml-small` model (q8_0-quantized), with beam
  search (`beam_size = 8`) and `language` hardcoded to `"de"`.
- **`App.kt`** — the shared Compose Multiplatform UI wiring all of the
  above together, plus mic-permission handling and stop-on-background
  behavior.

Everything except the two native `WhisperEngine` bindings and the two
`AudioCapture` bindings is shared Kotlin in `commonMain`.

## Comparing engines by ear

A toggle next to the Record button switches the engine used for the next
recording. Every recording in the transcript is tagged with the engine that
produced it (a "Whisper"/"Clinical"/"Native" chip shown once at the start of
the recording, not repeated per segment), so their output can be compared by
ear on the same device.

- **Whisper** — the stock `ggml-small` pipeline described above.
- **Clinical** — the same pipeline with a clinical-context fine-tune of
  `ggml-small` swapped in (`scripts/build-clinical-model.sh`; the fine-tuned
  weights come from the separate `medical-data-sources` project). Decoding
  parameters are identical to Whisper — only the model file differs.
  Switching to or from this option reloads the model (~1–3 s, with a
  "Loading model…" notice); the toggles and Record button are disabled
  during the reload.
- **Native** (Android only) — Android's on-device `SpeechRecognizer`
  (`AndroidSpeechRecognizerEngine`), gated on
  `SpeechRecognizer.isOnDeviceRecognitionAvailable` (requires Android 12/API
  31+) so it never falls back to cloud-based recognition. See
  `docs/superpowers/specs/2026-08-24-native-asr-comparison-design.md`.

iOS shows the Whisper/Clinical toggle but not Native.

## Tech stack

- Kotlin 2.4.10, Kotlin Multiplatform, Compose Multiplatform 1.11.1
- Android Gradle Plugin 9.1.1, Android `minSdk` 26 / `compileSdk` 36 /
  `targetSdk` 36
- iOS deployment target 15.0
- [whisper.cpp](https://github.com/ggml-org/whisper.cpp) (vendored as a git
  submodule, MIT-licensed), ggml multilingual `small` model, q8_0-quantized
  to ~252 MiB (from the ~466 MiB fp16 original, MIT-licensed, from
  [`ggerganov/whisper.cpp` on Hugging Face](https://huggingface.co/ggerganov/whisper.cpp))
  by `scripts/download-model.sh` - roughly half the decode time with no
  measurable quality loss

## Prerequisites

- **JDK 17**
- **Android SDK** with **NDK** and **CMake** installed, and `ANDROID_HOME`
  or `local.properties` (`sdk.dir=...`) pointing at it
- **Xcode** (for iOS) with command-line tools
- `git` with submodule support
- `curl` (used by the model download script)

## Setup

```bash
git clone --recurse-submodules <this-repo>
cd mobile-voice-2-text-poc
# If you cloned without --recurse-submodules:
git submodule update --init

./scripts/download-model.sh
./scripts/download-vad-model.sh
```

The first script downloads the ~466 MiB German multilingual `ggml-small.bin`
model, quantizes it to q8_0 (~252 MiB, via a locally-built `whisper-quantize`
from the vendored whisper.cpp - requires `cmake` on `PATH`, or point at one
with `CMAKE=/path/to/cmake`), and places a copy at both platforms' expected
asset locations (`composeApp/src/androidMain/assets/models/ggml-small.bin`
and `iosApp/iosApp/Resources/ggml-small.bin`). The model is gitignored —
every fresh checkout needs to run this script once.

The second script downloads the small Silero VAD model
(`ggml-silero-v6.2.0.bin`, a few MB) used for real-time speech/silence
segmentation, and places a copy at both platforms' expected asset locations.
It's also gitignored — run it once per fresh checkout, same as the model
script above.

### Optional: the clinical fine-tune

The "Clinical" engine toggle needs a third model asset
(`ggml-small-clinical-de.bin`). It isn't downloadable — it's converted from a
fine-tuned Hugging Face checkpoint produced by the separate
`medical-data-sources` project. Copy that checkpoint into `models-src/`
(gitignored) and run the build script:

```bash
cp -r <medical-data-sources>/data/finetune/whisper-small-clinical-de models-src/
./scripts/build-clinical-model.sh
```

It converts the safetensors checkpoint to ggml (`convert-h5-to-ggml.py` from
the vendored whisper.cpp, plus one asset file fetched from `openai/whisper`),
q8_0-quantizes it the same way `download-model.sh` does, and places a copy at
both platforms' asset locations. If `cmake` + a C/C++ toolchain aren't
available it ships the unquantized fp16 model instead (~465 MiB vs ~252 MiB);
delete
`composeApp/src/androidMain/assets/models/ggml-small-clinical-de.bin` and
re-run on a machine with the toolchain to swap in the quantized version.
Without this asset the app still builds and runs — only the Clinical toggle
fails (surfaced as an on-screen error when selected).

## Building & running — Android

```bash
./gradlew :composeApp:assembleDebug
./gradlew :composeApp:installDebug   # with a device/emulator connected
```

The first build compiles whisper.cpp from source via CMake/NDK for
`arm64-v8a`, which takes a few minutes; subsequent builds are incremental.

Run the unit tests (pure-Kotlin `SpeechSegmenter`/`TranscriptionSession`
logic):

```bash
./gradlew :composeApp:testDebugUnitTest
```

## Building & running — iOS

whisper.cpp's iOS static libraries aren't built by Gradle — build them once
before opening Xcode:

```bash
./scripts/build-whisper-ios.sh
```

This produces static libs for both the device (`iosArm64`) and the Apple
Silicon simulator (`iosSimulatorArm64`) under `third_party/whisper.cpp/build-ios-device`
and `build-ios-sim`. Re-run it whenever the whisper.cpp submodule commit
changes.

Then either build via Gradle/Kotlin only:

```bash
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64
```

or open the full app in Xcode and run it:

```bash
open iosApp/iosApp.xcodeproj
```

(select an `iosArm64`-compatible simulator or a real device; `iosX64`
Intel-simulator targets are not supported — see Known limitations).

## Project layout

```
composeApp/
  src/commonMain/    shared Kotlin: UI, TranscriptionSession, SpeechSegmenter,
                      expect declarations
  src/androidMain/    Android actuals + JNI/CMake bridge to whisper.cpp
  src/iosMain/        iOS actuals + Kotlin/Native cinterop bridge
  src/commonTest/     unit tests
  src/androidInstrumentedTest/  on-device instrumented test for WhisperEngine
iosApp/               Xcode project (SwiftUI shell hosting the Compose UI)
third_party/whisper.cpp/   vendored submodule
scripts/               model download + iOS whisper.cpp build scripts
docs/superpowers/      design doc and implementation plan
```

## Known limitations (PoC scope)

- iOS Simulator cannot run whisper.cpp's GPU/Metal backend for this model
  size (a Simulator-only `MTLSimDevice` limitation); real devices are
  unaffected.
- No persistence, no transcript export, no language selection (German
  only) — all deliberately out of scope, see the design doc's "Non-goals".
- `iosX64` (Intel simulator) is not built or supported.

## License notes

whisper.cpp and the ggml Whisper models are MIT-licensed. See
`third_party/whisper.cpp` for its own license.