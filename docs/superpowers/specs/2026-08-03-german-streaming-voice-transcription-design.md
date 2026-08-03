# German Streaming Voice-to-Text PoC — Design

Date: 2026-08-03

## Purpose

Prove that fully on-device, streaming speech-to-text is feasible for German
clinical dictation on mobile — with no audio or transcript data ever leaving
the device, and no dependency on a third-party cloud service.

This is a proof of concept, not a product. Scope is deliberately narrow:
demonstrate the pipeline works end-to-end on both Android and iOS with
subjectively acceptable German transcription quality.

## Goals

- Fully on-device speech-to-text — no network calls, no cloud STT vendor.
- German language support.
- "Live-ish" transcription: text appears while the user is still speaking,
  segmented at natural pauses, rather than only after recording stops.
- Runs on both Android and iOS from a shared Kotlin Multiplatform codebase.

## Non-goals

- Production-grade UX, persistence, or export of transcripts.
- Formal accuracy measurement (e.g. word-error-rate) against a reference
  transcript set.
- Support for languages other than German.
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

## Why sherpa-onnx over Whisper

Whisper (via whisper.cpp) was the first candidate considered: it has the
largest community and strongest general multilingual accuracy reputation of
the open options. It was set aside because it is architecturally a **batch**
model — it processes fixed windows, not a true streaming design. Achieving
"live-ish" transcription with Whisper would mean bolting a custom
pause-detector (VAD) onto a model that wasn't built for incremental use,
with known rough edges (repetition/instability at chunk boundaries).

**sherpa-onnx** (next-generation Kaldi, streaming transformer models via
ONNX Runtime) was chosen instead because:

- It is purpose-built for real-time streaming ASR, including endpoint
  (utterance-boundary) detection as a first-class runtime feature — so we
  get "live-ish" segmentation for free, rather than building our own VAD.
- It ships official, actively maintained bindings for both Android (Kotlin/
  JNI) and iOS (C API), from the same upstream project — not third-party
  wrappers of divergent quality/versions.
- It includes German streaming models.
- It supports hotword/vocabulary biasing, relevant to medical terminology.

Vosk (Kaldi-based) was also considered: it is purpose-built for offline
mobile ASR with ready German models and official mobile bindings, but its
older DNN-HMM architecture has a lower accuracy ceiling, particularly on
out-of-vocabulary terms like medical drug names, and the ecosystem has less
ongoing research investment than transformer-based ASR.

**Exact model choice** (which specific pretrained streaming German model
from sherpa-onnx's model zoo) is not locked in this design — it's a research
spike during implementation, decided by testing candidates for size/latency/
accuracy trade-offs on real devices.

## Architecture

- **Project structure**: Kotlin Multiplatform targeting Android and iOS. A
  `shared` module holds common logic; `androidApp`/`iosApp` hold thin
  platform entry points and UI.
- **Native ASR engine**: sherpa-onnx's official prebuilt binaries and
  bindings — its Kotlin/JNI API on Android, its C API (via Kotlin/Native
  cinterop) on iOS.
- **Audio capture**: platform-specific mic capture (`AVAudioEngine` on iOS,
  `AudioRecord` on Android) behind an `expect`/`actual` interface, producing
  raw PCM frames at the sample rate the model expects (typically 16kHz
  mono).
- **Pipeline**: PCM frames → streaming recognizer (fed continuously) →
  recognizer emits partial hypotheses (growing live text) and endpoint
  signals (segment boundaries: finalized text + reset for the next
  utterance).
- **No network calls, ever** — the recognizer runs fully offline. There is
  no code path that could transmit audio or transcript data anywhere,
  satisfying the no-third-party-access requirement architecturally.

## Components & Data Flow

**Components:**

- **`AudioCapture`** (`expect`/`actual`) — platform mic session, emits raw
  PCM buffers as they arrive (roughly every ~100ms).
- **`StreamingSpeechRecognizer`** (`expect`/`actual`) — thin wrapper around
  sherpa-onnx's streaming recognizer API per platform. Exposes: feed a PCM
  buffer, read the current partial hypothesis, check/consume an endpoint
  event.
- **`TranscriptionSession`** (pure Kotlin, `commonMain`, no platform
  dependency) — orchestrates the loop: pull frames from `AudioCapture` →
  feed to `StreamingSpeechRecognizer` → read partial text → on endpoint,
  move the finalized segment into transcript history and reset the stream
  for the next utterance. Being platform-independent, it's unit-testable
  against fakes without real audio hardware or the ONNX model.
- **UI state** — a state holder (e.g. `StateFlow`) with: list of finalized
  segments, current in-progress partial text, and recording on/off state.
  The UI renders this reactively.

**Data flow:**

1. Tap Record → `AudioCapture` starts the mic session.
2. Each PCM buffer → fed into the recognizer → session reads back the
   partial hypothesis → UI's in-progress text updates live.
3. When the recognizer signals an endpoint (silence/pause) → session
   finalizes that segment's text, appends it to transcript history, resets
   the recognizer stream, and continues listening.
4. Tap Stop → `AudioCapture` stops, session flushes any trailing partial
   text as a final segment, recognizer resources are released.
5. Final transcript = finalized segments + any trailing partial, held only
   in memory. Closing the app discards it — no persistence.

## UI Scope

Bare minimum: a single screen with a record/stop button and a growing
transcript text view. No save, export, or history across sessions.

## Error Handling & Edge Cases

**Handled:**

- **Mic permission denied** — show a message plus a button that deep-links
  to the OS app settings screen (`UIApplication.openSettingsURLString` on
  iOS, an intent to `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` on
  Android), so the user can grant permission without hunting for it.
- **Model load failure** (missing/corrupt bundled model, init failure) —
  caught at app start or first use, surfaced as a clear error state rather
  than a crash.
- **No natural pause before Stop is tapped** — if the recognizer never
  signals an endpoint (continuous speech, or the user stops mid-utterance),
  the session force-finalizes whatever partial text exists when Stop is
  pressed, so nothing is silently dropped.
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
- Recognizer falling behind real-time on slower devices — observed and
  noted as a finding, not engineered around.

## Testing & Success Criteria

- **Unit tests**: `TranscriptionSession` is tested with a fake `AudioCapture`
  (feeding canned PCM data) and a fake `StreamingSpeechRecognizer` (returning
  scripted partials/endpoints), verifying segment finalization,
  force-finalization on Stop, and UI state transitions — without touching
  real audio or the ONNX model.
- **Manual accuracy check**: run the app on a real Android device and a real
  iOS device, feed it a small set of German audio clips (general speech plus
  some medical terms — sourced or recorded as part of implementation, since
  no test set exists yet), and judge transcription quality by ear/eye.
- **Success criteria**: the app builds and runs on both platforms, a live
  transcript appears while speaking, and the output is subjectively "good
  enough" German transcription. No formal accuracy metric (e.g. WER) is
  required for this PoC.
