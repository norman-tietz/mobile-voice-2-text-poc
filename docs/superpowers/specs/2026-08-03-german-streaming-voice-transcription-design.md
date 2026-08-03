# German Streaming Voice-to-Text PoC — Design

Date: 2026-08-03

## Purpose

Prove that fully on-device speech-to-text is feasible for German clinical
dictation on mobile — with no audio or transcript data ever leaving the
device, and no dependency on a third-party cloud service.

This is a proof of concept, not a product. Scope is deliberately narrow:
demonstrate the pipeline works end-to-end on both Android and iOS with
subjectively acceptable German transcription quality.

## Goals

- Fully on-device speech-to-text — no network calls, no cloud STT vendor.
- German language support.
- "Live-ish" transcription: as the user speaks, each pause in speech
  finalizes and transcribes the segment just spoken, so the transcript grows
  in pieces while recording continues — rather than waiting for the whole
  recording to end before showing any text. Note: within a segment (before a
  pause is detected), no partial/word-by-word text is shown; text appears
  once that segment finishes processing, right after the pause.
- Runs on both Android and iOS from a shared Kotlin Multiplatform codebase.

## Non-goals

- Production-grade UX, persistence, or export of transcripts.
- Formal accuracy measurement (e.g. word-error-rate) against a reference
  transcript set.
- Support for languages other than German.
- True word-by-word streaming (partial hypotheses mid-utterance, before a
  pause is detected) — see the "live-ish" note in Goals.
- Handling audio interruptions (calls, other apps taking the mic),
  backgrounding during recording, or unsupported/low-end devices gracefully.
- Medical-vocabulary tuning (custom lexicon/fine-tuning) — the PoC evaluates
  out-of-the-box accuracy on medical terms, but does not invest in improving
  it yet.

## Why on-device, and why not the platform-native recognizers

The hard requirement — no sensitive audio/text is accessible to any third
party — ruled out cloud STT APIs outright. It also weighed against relying on
the OS-vendor recognizers (`SFSpeechRecognizer` on iOS, `SpeechRecognizer` on
Android):

- Both default to sending audio to the vendor's servers; an explicit
  on-device flag is needed to avoid this, and behavior differs by platform.
  Guaranteed on-device recognition on Android didn't exist before Android 12
  (API 31), and support is inconsistent across OEMs/devices below that.
- Even when forced on-device, the recognition code itself is closed-source —
  compliance rests on trusting the vendor's documentation, not on anything
  we can audit.
- Neither platform offers real vocabulary customization for on-device mode,
  which matters for medical terminology.

A bundled, open-source model that we run ourselves gives a stronger,
auditable compliance story (the exact code path touching audio is ours to
inspect) and consistent behavior across both platforms.

## Model choice: Whisper (whisper.cpp), after a sherpa-onnx detour

**sherpa-onnx** (next-generation Kaldi, streaming transformer models via
ONNX Runtime) was the first choice, because it's purpose-built for real-time
streaming ASR with endpoint (utterance-boundary) detection built in — which
would have given "live-ish" segmentation for free, including true
word-by-word partial hypotheses. It was reversed after implementation
research found:

- **No German model in sherpa-onnx's own official model zoo.** Its
  documented streaming models cover Chinese, English, Korean, French,
  Bengali, Russian, etc. — not German.
- The only German streaming model found, **Kroko-ASR** (by a company called
  Banafo), is a third-party project. Its official distribution ships a
  proprietary `.data` format with its own decoder script — **not** standard
  sherpa-onnx files — so it isn't natively sherpa-onnx-compatible despite
  being built on sherpa-onnx's engine.
- The only sherpa-onnx-native version of that German model (encoder/decoder/
  joiner ONNX + tokens.txt) found was an **unofficial third-party conversion**
  on Hugging Face, unverified by Kroko or sherpa-onnx maintainers, and
  relicensed (claiming Apache-2.0) in a way that conflicts with Kroko's own
  stated terms (CC-BY-SA community / commercial OEM license). Building the
  PoC's core pipeline on an unvetted, ambiguously-licensed conversion was
  judged too risky, including for a PoC.

**Whisper (via whisper.cpp)** was chosen instead:

- Officially distributed by OpenAI/the `ggml-org/whisper.cpp` project (no
  third-party mirror risk); model weights are MIT-licensed, free for
  commercial and non-commercial use.
- Strong, well-documented multilingual accuracy including German.
- `whisper.cpp` ships official build paths for both Android (JNI, via
  Gradle/NDK — see `examples/whisper.android.java`) and iOS (an
  `xcframework` via `build-xcframework.sh`, used from Swift — see
  `examples/whisper.swiftui`). We write our own thin binding against its
  public C API rather than depending on a third-party wrapper, for the same
  auditability reasons discussed above.

**Trade-off accepted**: Whisper is architecturally a **batch** model — it
transcribes a fixed buffer in one pass, with no built-in streaming or
endpoint detection. To get "live-ish" behavior, we detect pauses ourselves
(a simple RMS energy-based silence detector, implemented in shared Kotlin)
and transcribe each pause-delimited segment as soon as it's detected, while
recording continues. This means segments finalize a beat after each pause
(no mid-utterance partial text), rather than the smoother true-streaming
experience sherpa-onnx would have offered.

**Model size**: the multilingual `small` ggml model (~466 MiB) is the
starting point, favoring accuracy per the earlier decision to prioritize
transcription quality over latency/app size. `medium` (~1.5 GiB) is a
fallback to try if `small`'s German accuracy is not good enough on real
devices. Both are downloaded from the official
`https://huggingface.co/ggerganov/whisper.cpp` model repo.

## Architecture

- **Project structure**: Kotlin Multiplatform targeting Android and iOS. A
  `shared` module holds common logic and the UI (see UI Scope below);
  `androidApp`/`iosApp` hold thin platform entry points.
- **Native ASR engine**: `whisper.cpp`'s C API, bundled and compiled
  ourselves — via NDK/CMake on Android, via its `build-xcframework.sh`
  output on iOS — bound into Kotlin through a single `expect`/`actual`
  interface (JNI on Android, Kotlin/Native cinterop on iOS).
- **Audio capture**: platform-specific mic capture (`AVAudioEngine` on iOS,
  `AudioRecord` on Android) behind an `expect`/`actual` interface, producing
  raw PCM frames at 16kHz mono (the sample rate Whisper expects).
- **Pause detection (VAD)**: a simple RMS energy-based silence detector,
  implemented once in shared Kotlin (`commonMain`) since it operates purely
  on PCM sample data — no platform dependency needed.
- **Pipeline**: PCM frames accumulate into the current segment's buffer;
  the pause detector watches for a trailing silence window; on detection,
  the accumulated buffer is handed to the Whisper engine for transcription,
  the result is appended to the transcript, and a new segment buffer starts.
- **No network calls, ever** — the recognizer runs fully offline. There is
  no code path that could transmit audio or transcript data anywhere,
  satisfying the no-third-party-access requirement architecturally.

## Components & Data Flow

**Components:**

- **`AudioCapture`** (`expect`/`actual`) — platform mic session, emits raw
  PCM buffers as they arrive (roughly every ~100ms).
- **`PauseDetector`** (`commonMain`, pure Kotlin) — consumes PCM buffers,
  tracks trailing silence duration via RMS energy against a threshold, and
  reports when a pause boundary is reached.
- **`WhisperEngine`** (`expect`/`actual`) — thin wrapper around
  `whisper.cpp`'s C API per platform. Exposes one operation: transcribe a
  complete PCM buffer (a finalized segment) and return its text. This is a
  batch call, not a streaming one — it's invoked once per completed segment.
- **`TranscriptionSession`** (pure Kotlin, `commonMain`, no platform
  dependency) — orchestrates the loop: pull frames from `AudioCapture` →
  feed to `PauseDetector` and accumulate into the current segment buffer →
  on pause, send the buffer to `WhisperEngine` → append the returned text to
  transcript history → start a new segment buffer. Being platform-independent,
  it's unit-testable against fakes without real audio hardware or the model.
- **UI state** — a state holder (e.g. `StateFlow`) with: list of finalized
  segments, recording on/off state, and any error state (permission denied,
  model load failure). The UI renders this reactively.

**Data flow:**

1. Tap Record → `AudioCapture` starts the mic session.
2. Each PCM buffer → appended to the current segment buffer, and fed to
   `PauseDetector`.
3. When `PauseDetector` signals a pause boundary → the session sends the
   accumulated segment buffer to `WhisperEngine.transcribe(...)` → the
   returned text is appended to transcript history → the UI's transcript
   view updates → a new, empty segment buffer starts accumulating.
4. Tap Stop → `AudioCapture` stops; if the current segment buffer has any
   audio in it (even without a detected pause), it's force-transcribed and
   appended as a final segment; `WhisperEngine` resources are released.
5. Final transcript = all finalized segments, held only in memory. Closing
   the app discards it — no persistence.

## UI Scope

Bare minimum: a single screen with a record/stop button and a growing
transcript text view. No save, export, or history across sessions.

Built with **Compose Multiplatform**, shared in the `shared` module for both
platforms — one UI codebase rather than separate Jetpack Compose / SwiftUI
implementations, consistent with maximizing shared code for a screen this
simple.

## Error Handling & Edge Cases

**Handled:**

- **Mic permission denied** — show a message plus a button that deep-links
  to the OS app settings screen (`UIApplication.openSettingsURLString` on
  iOS, an intent to `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` on
  Android), so the user can grant permission without hunting for it.
- **Model load failure** (missing/corrupt bundled model, init failure) —
  caught at app start or first use, surfaced as a clear error state rather
  than a crash.
- **No pause detected before Stop is tapped** — if the user stops mid-
  utterance (no trailing silence long enough to trigger a pause boundary),
  the session force-transcribes whatever audio is in the current segment
  buffer when Stop is pressed, so nothing is silently dropped.
- **Silence-only recording** — no speech detected produces an empty
  transcript, not an error.
- **App backgrounded mid-recording** — recording stops automatically when
  the app leaves the foreground, same as if Stop were tapped. Supporting
  true background capture is out of scope (see Non-goals).
- **No logging of sensitive content** — debug/diagnostic logging must never
  include transcript text or raw audio; this is a hard implementation
  constraint, not just a nice-to-have, since it would otherwise undercut the
  no-data-leaves-the-device premise even via local logs.

**Explicitly out of scope** (acceptable to leave unhandled for this PoC):

- Audio interruptions (incoming call, another app grabbing the mic).
- Unsupported/low-end devices (missing CPU features, insufficient RAM).
- The Whisper engine falling behind real-time on slower devices (a segment
  taking noticeably long to transcribe after its pause) — observed and
  noted as a finding, not engineered around.
- Tuning the pause-detection threshold/timing for varied recording
  conditions (background noise, different mic sensitivities) — a fixed,
  reasonable default is used; robustness across environments is not a goal.

## Testing & Success Criteria

- **Unit tests**: `TranscriptionSession` and `PauseDetector` are pure Kotlin
  and tested with a fake `AudioCapture` (feeding canned PCM data) and a fake
  `WhisperEngine` (returning scripted text per call), verifying segment
  boundary detection, force-transcription on Stop, and UI state transitions
  — without touching real audio or the actual model.
- **Manual accuracy check**: run the app on a real Android device and a real
  iOS device, feed it a small set of German audio clips (general speech plus
  some medical terms — sourced or recorded as part of implementation, since
  no test set exists yet), and judge transcription quality by ear/eye.
- **Success criteria**: the app builds and runs on both platforms, the
  transcript grows in segments while speaking (per the "live-ish" behavior
  defined in Goals), and the output is subjectively "good enough" German
  transcription. No formal accuracy metric (e.g. WER) is required for this
  PoC.
