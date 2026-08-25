# Native ASR Comparison — Design

Date: 2026-08-24

## Purpose

The [German Streaming Voice-to-Text PoC design](2026-08-03-german-streaming-voice-transcription-design.md)
deliberately ruled out platform-native speech recognizers (`SpeechRecognizer`
on Android, `SFSpeechRecognizer` on iOS) in favor of a bundled, on-device
Whisper model, for compliance/auditability reasons. To judge whether that
trade-off cost meaningful transcription quality, this change adds Android's
native `SpeechRecognizer` as a second, switchable ASR engine, so the two can
be compared side by side in the same app session.

This is scoped to Android only — no iOS native engine is added.

## Goals

- Let the user switch between the existing Whisper engine and Android's
  on-device `SpeechRecognizer` from the recording screen, per recording.
- Clearly label which engine produced each transcript segment, so segments
  from both engines remain distinguishable across a session.
- Preserve the original no-data-leaves-the-device guarantee for the native
  path: only Android's guaranteed on-device recognition is used, never the
  cloud-backed default.

## Non-goals

- An iOS native engine (`SFSpeechRecognizer`). The engine toggle does not
  appear on iOS.
- Formal accuracy comparison (WER or similar) between engines — this is for
  subjective, by-ear comparison, consistent with the original PoC's success
  criteria.
- Any change to the existing Whisper pipeline's behavior.

## Why native `SpeechRecognizer` doesn't fit the existing pipeline

The existing pipeline (`AudioCapture` → `PauseDetector` → `TranscriptionSession`
→ `Transcriber`/`WhisperEngine`) is a **pull** model: the app owns the mic,
captures PCM continuously, and periodically hands a finalized buffer to a
batch transcriber.

Android's `SpeechRecognizer` is a **push** model: it owns the microphone
itself internally, performs its own endpoint (utterance-boundary) detection,
and delivers finalized text asynchronously via `RecognitionListener`
callbacks. It cannot be fed externally-captured PCM buffers through the
public API. So native mode does not reuse `AudioCapture`/`PauseDetector`/
`TranscriptionSession` — it runs its own, parallel session loop.

## On-device guarantee

Android's `SpeechRecognizer` defaults to sending audio to the vendor's cloud
service; forcing on-device is only reliably possible on API 31+ (Android 12).
Since preserving "no audio leaves the device" is the reason this whole PoC
exists, native mode uses the stronger guarantee:

- `SpeechRecognizer.isOnDeviceRecognitionAvailable(context)` gates
  availability. If false (pre-API-31 device, or unsupported OEM), the native
  option is disabled in the UI rather than silently falling back to cloud
  recognition.
- `SpeechRecognizer.createOnDeviceSpeechRecognizer(context)` is used to
  create the recognizer — this is a guarantee, unlike the `EXTRA_PREFER_OFFLINE`
  intent extra, which is only a hint and can still fall back to a cloud
  round-trip on some devices/versions.
- Recognition intent uses `LANGUAGE_MODEL_FREE_FORM` and
  `EXTRA_LANGUAGE = "de-DE"`, matching the Whisper path's German-only scope.

## Components

- **`NativeAsrEngine`** (`commonMain`, plain interface — not `expect`/`actual`,
  since only Android implements it):
  ```kotlin
  interface NativeAsrEngine {
      fun isAvailable(): Boolean
      suspend fun start(onSegment: (String) -> Unit, onError: (String) -> Unit)
      suspend fun stop()
  }
  ```
- **`AndroidSpeechRecognizerEngine`** (`androidMain`) — implements
  `NativeAsrEngine` using `android.speech.SpeechRecognizer`. Holds the
  recognizer instance and a `RecognitionListener`. All `SpeechRecognizer`
  calls happen on the main thread (an Android platform requirement); `start`/
  `stop` hop via `Dispatchers.Main` internally so callers don't need to know
  this.
- **`MainActivity`** constructs an `AndroidSpeechRecognizerEngine` and passes
  it to `App`. **`MainViewController`** (iOS) passes `null`.

## Session behavior

**`start(onSegment, onError)`:**

1. Begins listening (`startListening(intent)`).
2. On `onResults`: extract the top hypothesis, call `onSegment(text)`, then
   immediately call `startListening(intent)` again if the session is still
   active — this continues capturing segment after segment for the rest of
   the recording, mirroring the "transcript grows in segments while
   recording" behavior of the Whisper path.
3. On recoverable errors (`ERROR_NO_MATCH`, `ERROR_SPEECH_TIMEOUT` — i.e.
   silence, no speech detected): restart listening without emitting a
   segment, consistent with the Whisper path's "silence produces an empty
   transcript, not an error."
4. On any other error (`ERROR_RECOGNIZER_BUSY`, `ERROR_INSUFFICIENT_PERMISSIONS`,
   etc.): call `onError(message)`. `App` maps this to `UiState.Error`, ending
   the recording, the same way a Whisper model-load failure does today.

**`stop()`:**

1. Calls `stopListening()` — per the Android API, this signals end-of-speech
   and still delivers a final `onResults` callback for whatever audio was
   captured so far (unlike `cancel()`, which discards it). This is the
   native-mode equivalent of the Whisper path's force-transcribe-of-pending-
   buffer on Stop.
2. Suspends until that final callback (`onResults` or a terminal error)
   arrives, then calls `destroy()` on the recognizer.
3. Callers (`App.stopRecording()`) `await` this exactly as they already
   `await activeSession.stop()` for the Whisper path.

## UI changes (`App.kt`)

- `enum class AsrEngine { WHISPER, NATIVE }`; new state
  `var selectedEngine by remember { mutableStateOf(AsrEngine.WHISPER) }`.
- New `nativeAsr: NativeAsrEngine?` parameter on `App`. A small two-button
  toggle ("Whisper" / "Native") is rendered next to the Record/Stop button
  only when `nativeAsr != null` — this hides it entirely on iOS without any
  explicit platform check in common code.
  - Filled style for the selected engine, outlined for the other.
  - Both buttons disabled unless `uiState == UiState.Idle` (switching engines
    mid-recording is not supported).
  - The "Native" button is additionally disabled if
    `nativeAsr?.isAvailable() != true`.
- `startRecording()` and `stopRecording()` branch on `selectedEngine`:
  - `WHISPER`: existing `AudioCapture`/`TranscriptionSession` path, unchanged.
  - `NATIVE`: calls `nativeAsr.start(...)` / `nativeAsr.stop()` instead;
    no `AudioCapture`, `PauseDetector`, or `WhisperEngine` involved.
- Every segment appended to `transcript`, from either engine, is prefixed
  with which engine produced it: `"[Whisper] …"` / `"[Native] …"`. This
  satisfies showing the method on screen before the recognized text itself.

## Edge-to-edge layout

The app targets SDK 36; on Android 15+ (API 35+), edge-to-edge display is
enforced for apps at this target SDK and can no longer be opted out of. The
root `Column` in `App.kt` currently only applies a fixed
`Modifier.padding(16.dp)`, which is not inset-aware — under enforced
edge-to-edge, transcript text and the record/stop controls can render
partially under the status bar or gesture-navigation bar.

Fix: add `Modifier.safeDrawingPadding()` to the root `Column` in `App.kt`
(`commonMain`). This is Compose Multiplatform's cross-platform safe-area API
— it resolves to the real system bar/cutout/IME insets on Android, and is a
no-op where no such insets apply (iOS, older Android). Single-line, common-code
change; no platform-specific insets handling needed.

## Logging

The existing hard constraint carries over unchanged: `debugLog` calls in the
new native-mode code log control flow only (state transitions, callback
names, error codes) — never the recognized text itself, even though it's
already shown on screen. This matches the existing Whisper-path logging.

## Error Handling & Edge Cases

**Handled:**

- **On-device recognition unavailable** (pre-API-31, unsupported OEM) — the
  "Native" toggle option is disabled; the user cannot select it.
- **Silence / no speech detected** — treated as empty, not an error (same
  policy as Whisper mode).
- **Recognizer error mid-session** (busy, permission revoked mid-session,
  etc.) — surfaces as `UiState.Error`, ending the recording, same as a fatal
  Whisper failure.
- **Stop mid-utterance** — `stopListening()` still delivers a final result
  for whatever was captured, so nothing in the current utterance is silently
  dropped, mirroring the Whisper path's force-transcribe-on-Stop.
- **App backgrounded mid-recording** — the existing `AppLifecycleObserver`
  path calls `stopRecording()` regardless of engine, so this applies to
  native mode unchanged.
- **Edge-to-edge display** (Android 15+, enforced at this app's targetSdk) —
  see "Edge-to-edge layout" above; transcript and controls stay clear of
  system bars.

**Explicitly out of scope:**

- True continuous/streaming recognition — native mode is still segment by
  segment (one `SpeechRecognizer` utterance per segment), same granularity
  as the Whisper path, just via a different segmentation mechanism.
- Any iOS native engine.
- Formal WER/accuracy comparison between engines.

## Testing & Success Criteria

`SpeechRecognizer` cannot be meaningfully unit-tested (no fake-able seam,
same situation as `WhisperEngine`'s native binding today). Verification is
manual, on a real Android device:

- Toggle is visible on Android, hidden on iOS.
- "Native" is disabled on a device/OS version without on-device recognition,
  enabled otherwise.
- Recording with Native selected produces `[Native] …`-prefixed segments;
  switching to Whisper on a subsequent recording produces `[Whisper] …`
  segments.
- Stopping mid-utterance in native mode still yields a final segment for
  captured speech.
- Backgrounding the app mid-recording in native mode stops recording, same
  as Whisper mode.
- On an Android 15+ device/emulator, transcript text and the record/toggle
  controls are fully visible, not obscured by the status bar or gesture nav
  bar.

**Success criteria**: both engines are selectable and usable from the same
screen, segments are unambiguously labeled by engine, and no audio leaves
the device in either mode — enabling a subjective side-by-side quality
comparison, which was the actual goal of this change.
