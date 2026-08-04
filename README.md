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
AudioCapture (mic) → resampleTo16k → TranscriptionSession → PauseDetector
                                            ↓
                                      WhisperEngine (whisper.cpp)
                                            ↓
                                    Compose UI (transcript segments)
```

- **`AudioCapture`** (`expect`/`actual`) — captures microphone audio.
  Android via `AudioRecord`, iOS via `AVAudioEngine`.
- **`PauseDetector`** — RMS-based trailing-silence detection; signals a
  pause once ~700ms of near-silence is observed.
- **`TranscriptionSession`** — buffers audio and triggers transcription of
  the buffered segment whenever a pause is detected, or when recording
  stops with audio still pending.
- **`WhisperEngine`** (`expect`/`actual`) — runs whisper.cpp natively.
  Android via a JNI/CMake binding, iOS via Kotlin/Native cinterop. Both
  bind the same vendored `whisper.cpp` C library and the same bundled
  German multilingual `ggml-small` model, with `language` hardcoded to
  `"de"`.
- **`App.kt`** — the shared Compose Multiplatform UI wiring all of the
  above together, plus mic-permission handling and stop-on-background
  behavior.

Everything except the two native `WhisperEngine` bindings and the two
`AudioCapture` bindings is shared Kotlin in `commonMain`.

## Tech stack

- Kotlin 2.4.10, Kotlin Multiplatform, Compose Multiplatform 1.11.1
- Android Gradle Plugin 9.1.1, Android `minSdk` 26 / `compileSdk` 36 /
  `targetSdk` 36
- iOS deployment target 15.0
- [whisper.cpp](https://github.com/ggml-org/whisper.cpp) (vendored as a git
  submodule, MIT-licensed), ggml multilingual `small` model (~466 MiB,
  MIT-licensed, from
  [`ggerganov/whisper.cpp` on Hugging Face](https://huggingface.co/ggerganov/whisper.cpp))

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
```

The last step downloads the ~466 MiB German multilingual `ggml-small.bin`
model and places a copy at both platforms' expected asset locations
(`composeApp/src/androidMain/assets/models/ggml-small.bin` and
`iosApp/iosApp/Resources/ggml-small.bin`). The model is gitignored — every
fresh checkout needs to run this script once.

## Building & running — Android

```bash
./gradlew :composeApp:assembleDebug
./gradlew :composeApp:installDebug   # with a device/emulator connected
```

The first build compiles whisper.cpp from source via CMake/NDK for
`arm64-v8a`, which takes a few minutes; subsequent builds are incremental.

Run the unit tests (pure-Kotlin `PauseDetector`/`TranscriptionSession`
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
  src/commonMain/    shared Kotlin: UI, TranscriptionSession, PauseDetector,
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

- **No `CMAKE_BUILD_TYPE` is set for the Android native build**, so debug
  builds compile whisper.cpp unoptimized; transcription can be very slow
  (tens of seconds to minutes per segment) on a debug APK. This is under
  active investigation (see `[VoiceDebug]`-tagged Logcat output).
- Audio capture briefly blocks while a segment is transcribing (the
  pipeline is synchronous), so speech immediately after a pause can be
  dropped until inference finishes.
- iOS Simulator cannot run whisper.cpp's GPU/Metal backend for this model
  size (a Simulator-only `MTLSimDevice` limitation); real devices are
  unaffected.
- No persistence, no transcript export, no language selection (German
  only) — all deliberately out of scope, see the design doc's "Non-goals".
- `iosX64` (Intel simulator) is not built or supported.

## License notes

whisper.cpp and the ggml Whisper models are MIT-licensed. See
`third_party/whisper.cpp` for its own license.