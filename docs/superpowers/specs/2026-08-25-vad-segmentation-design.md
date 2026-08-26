# Real-Time VAD Segmentation — Design

Date: 2026-08-25

## Purpose

Two problems surfaced while comparing the Whisper and native ASR engines
side by side:

1. Whisper's segment boundaries are noticeably worse than the native
   recognizer's, especially at cut points.
2. On-device testing (via a logcat-instrumented repro, see Evidence below)
   showed the transcript appearing to end well before the user actually
   stopped speaking — sometimes swallowing more than a whole sentence.

Root-cause investigation (not guesswork — see Evidence) found the real
problem: `PauseDetector` (a static RMS-threshold check) runs inside the
*same* coroutine that also calls the slow, blocking `transcribe()`. Whenever
transcribe() is slower than real-time (routinely true for `ggml-small` with
beam-search decoding — observed 5.7s to decode 2.1s of audio), audio queues
up unexamined while transcribe() runs, and PauseDetector only gets to look
at it in a rapid catch-up burst once transcribe() finishes. So "pause
detected" stops meaning "the user paused" and starts meaning "RMS dipped
below 0.02 somewhere in audio that was recorded seconds ago and is only
being examined now." This produces both symptoms above: mid-sentence cuts
(three of five segments in the repro ended with a literal "...", Whisper's
own signal that its input was cut off mid-thought) and a "Stopping…" state
that can take 10+ real seconds to resolve after the user taps Stop, making
it look like speech vanished when it was actually just severely delayed.

This change fixes both by (a) replacing the RMS threshold with whisper.cpp's
built-in VAD (Silero, ported to GGML) for real speech/silence discrimination,
and (b) restructuring the pipeline so segmentation timing is never coupled
to decode timing.

## Evidence

Captured via `adb logcat | grep VoiceDebug` during a live on-device Whisper
recording (existing `debugLog` instrumentation, no new logging needed to
diagnose this). Key observations:
- `nativeTranscribe returned after 5733ms` for a 33,600-sample (2.1s)
  segment, and `after 12651ms` for a 220,800-sample (13.8s) segment —
  decode is at or below real-time throughout.
- Two consecutive segments' text ("...sondern meine" / "völlig
  unabhängig...") are one continuous clause split at a boundary that fired
  only 3ms after the prior transcribe() call returned — i.e. the pause
  wasn't detected live, it was found while rapidly draining a backlog.
- `App.stopRecording: audio consumer drained after 11623ms` — the last
  segments arrived nearly 12 real seconds after Stop was tapped.
- No `AudioRecord` overflow/error logs, and every sample fed to the channel
  was eventually transcribed — confirming this is a timing/segmentation
  defect, not data loss in capture.

## Correction (2026-08-26)

The original version of this spec (below, largely unchanged) assumed
`whisper_vad_detect_speech_no_reset()` + repeated `whisper_vad_segments_from_probs()`
calls would let segment boundaries accumulate across calls as audio streamed
in — the same "condition on previous output" shape as the token-carryover
work elsewhere in this codebase. That assumption was implemented (Tasks
3/4/5/6/8) and reached final review before ever running on a real device —
the flagged spike ("Implementation risk to verify early" below) never
happened, because no Android device was reachable for the entire
implementation session. The final whole-branch review caught it instead,
and it was independently confirmed against the whisper.cpp source rather
than taken on faith:

- `whisper_vad_detect_speech_no_reset()` does `vctx->probs.resize(n_chunks)`
  (`whisper.cpp:5116`) — it **overwrites** the VAD context's probability
  buffer with only the current call's chunk. "no_reset" preserves the
  model's recurrent hidden state, not the probability trace
  `whisper_vad_segments_from_probs()` reads. With capture-sized (100ms)
  chunks, `whisper_vad_n_probs()` would only ever cover ~100ms per call —
  below whisper.cpp's own default 250ms minimum speech duration — so
  `segments()` would return empty essentially always in real use.
- `whisper_vad_segments_from_probs()` also returns the still-open trailing
  segment if the buffer ends mid-speech (`whisper.cpp:5343-5346`, "Handle
  the case if we're still in a speech segment at the end") — contradicting
  the "only appears once closed" contract the original interface below
  documented.
- Checked for precedent across the whole whisper.cpp tree: `tests/test-vad.cpp`
  only calls `whisper_vad_detect_speech()` once over a *whole* file before
  deriving segments from that one call; `examples/stream/stream.cpp` (the
  project's own live-mic streaming example) doesn't use `whisper_vad_*` at
  all — its `--vad` mode uses a separate, older, simple energy-based
  `vad_simple()` heuristic instead. Nothing in the codebase demonstrates the
  incremental-accumulation usage this plan assumed.

**The corrected pattern:** use whisper_vad as a real-time per-chunk
speech/silence *classifier*, not a segment-boundary deriver. Call `feed()`,
then immediately read `whisper_vad_probs()`/`whisper_vad_n_probs()` for that
chunk's probability (correctly informed by every prior chunk via the
preserved hidden state) — and keep the trailing-silence-duration bookkeeping
in Kotlin, the same shape the old `PauseDetector` already had, just with VAD
probability replacing RMS energy as the per-chunk signal.
`whisper_vad_segments_from_probs()` and its params (`min_silence_duration_ms`,
`speech_pad_ms`, etc.) drop out of the design entirely — every mention of
them in the sections below is superseded by the **Components (corrected)**
section, which replaces the original **Components** section's
`VoiceActivityDetector`/`WhisperVad`/`SpeechSegmenter` subsections. The
architecture diagram, the two-stage pipeline, and everything else in this
spec is unaffected — the fix in this section is *smaller* than the original
design, not larger, since it never depended on the broken assumption.

## Components (corrected)

**`VoiceActivityDetector`** (replaces the original interface below):

```kotlin
interface VoiceActivityDetector {
    // Classifies one capture chunk as speech or not, informed by every
    // chunk fed before it (the model's recurrent state carries forward
    // across calls even though its probability output does not).
    fun isSpeech(samples: FloatArray): Boolean
    fun resetState()
}
```

**`WhisperVad`** (replaces the original description below): wraps
`whisper_vad_context`, loaded from the bundled VAD model at startup
alongside the ASR model. `isSpeech()` calls
`whisper_vad_detect_speech_no_reset()` on exactly the given chunk, then
reads `whisper_vad_probs()`/`whisper_vad_n_probs()` immediately (before the
next call overwrites them) and returns `true` if any of that chunk's
sub-window probabilities meet whisper.cpp's default `threshold` (0.5, from
`whisper_vad_default_params()`) — a chunk this size (100ms) may span
multiple of the model's native windows, so "any window over threshold"
rather than an average avoids diluting a short loud syllable inside an
otherwise-quiet chunk. `whisper_vad_segments_from_probs()` is not called at
all in the corrected design.

**`SpeechSegmenter`** (replaces the original description below): now owns
the trailing-silence-duration state machine itself (feed a chunk to
`vad.isSpeech()`, accumulate `trailingSilenceMs`, cut when it crosses
`minSilenceDurationMs`) — structurally the same shape as the old
`PauseDetector`, including a `hadSpeech`-equivalent guard so `flush()`
doesn't hand a purely-silent trailing buffer to `transcribe()` (this also
supersedes the **Removed** section's claim that the hallucination guard is
"structurally unnecessary" — with the corrected design it's necessary
again, just re-implemented against VAD probability instead of RMS). Public
signature (`accept(samples): List<FloatArray>`, `flush(): FloatArray?`)
stays the same as the original design below, so nothing in `App.kt`'s
wiring needs to change — only `SpeechSegmenter`'s internals and the
`VoiceActivityDetector` contract underneath it do.

**Also found during final review, unrelated to the calling-pattern bug, both addressed alongside this correction:**
- The VAD model file is copied to `iosApp/iosApp/Resources/` by the
  download script, but was never added to `project.pbxproj`'s Resources
  build phase — Xcode doesn't bundle files just for existing on disk in a
  referenced folder. `resolveVadModelPath()` would throw on iOS at runtime.
  Needs a `PBXBuildFile`/`PBXFileReference` entry mirroring `ggml-small.bin`'s
  existing ones.
- Native handles (`WhisperEngine`, `WhisperVad`) are never `release()`'d in
  production code, only from instrumented tests — pre-existing for
  `WhisperEngine` (predates this plan), now doubled up with `WhisperVad`.
  `App.kt`'s `pipeline` `remember{}` needs a `DisposableEffect`/`onDispose`
  releasing both.

## Goals

- Segment boundaries reflect when the user actually paused, regardless of
  how far behind real-time `transcribe()` is running.
- Replace the static RMS threshold with real speech/non-speech
  discrimination (Silero VAD via whisper.cpp), reducing both false pauses
  (quiet speech misread as silence) and false continuations (background
  noise misread as speech).
- No new native dependency or platform-binding mechanism: reuse the
  whisper.cpp library and JNI/cinterop plumbing already vendored and linked
  on both platforms.

## Non-goals

- Changing the native (`SpeechRecognizer`) engine path — unaffected.
- True incremental/partial transcription (text updating word-by-word while
  still speaking). This fixes segmentation *accuracy* and *latency
  predictability*, not the transcribe-then-display granularity.
- Formal accuracy comparison (WER) between RMS and VAD segmentation.

## Feasibility check

whisper.cpp ships a full Silero VAD implementation (`whisper_vad_*` in
`whisper.h`) as part of the same `whisper` CMake target we already build and
link:
- Android: `nm -D` on the already-built `libwhisper.so` confirms
  `whisper_vad_*` symbols are present with no extra CMake flags.
- iOS: `scripts/build-whisper-ios.sh` only disables
  `WHISPER_BUILD_EXAMPLES`/`WHISPER_BUILD_TESTS`; VAD is core library code,
  compiled into `libwhisper.a` the same as on Android.
- Cinterop (`whisper.def`) binds the whole header (`headerFilter =
  whisper.h`), so `whisper_vad_*` declarations are already exposed to
  Kotlin/Native — same mechanism already used for the token-carryover work.
- The model is a small, separately downloadable ggml file
  (`third_party/whisper.cpp/models/download-vad-model.sh`, e.g.
  `ggml-silero-v6.2.0.bin`) — KBs, not the ~466MB of `ggml-small.bin`.

So this is additive to existing files/build wiring, not a new subsystem.

## Architecture

Split today's single "capture → detect-pause → transcribe" consumer into
two independent stages connected by a new channel:

```
AudioCapture (mic thread)
   --resampled 16kHz chunks-->  audioChannel (unlimited, as today)
        |
        v
   Segmenter coroutine (fast: VAD only, no decode)
   --finalized segment-->  segmentChannel (new, unlimited)
        |
        v
   Transcriber coroutine (slow: transcribe() calls, as today)
   --appends-->  UI transcript state
```

The Segmenter reads `audioChannel` in real time and never blocks on
`transcribe()`, so boundary decisions always reflect live audio. The
Transcriber can lag behind arbitrarily (as it already does today) without
corrupting *where* segments got cut — it only affects how soon a correctly-
cut segment's text appears.

## Components (original — superseded, kept for history; see "Components (corrected)" above)

**`VoiceActivityDetector`** (new, `commonMain` interface — kept thin and
fakeable so the segmentation policy stays unit-testable without a real VAD
model):

```kotlin
interface VoiceActivityDetector {
    // Feed newly captured samples; appends to the VAD's running trace.
    fun feed(samples: FloatArray)
    // Segment boundaries (seconds, relative to the last resetState()) that
    // whisper_vad_segments_from_probs() can currently derive from
    // everything fed so far. A segment only appears once its end has been
    // determined by minSilenceDurationMs of trailing low-probability audio
    // - the in-progress trailing segment (still being spoken) does not
    // appear until it closes.
    fun segments(minSilenceDurationMs: Int): List<ClosedFloatingPointRange<Float>>
    fun resetState()
}
```

**`WhisperVad`** (new, `expect`/`actual` like `WhisperEngine`): wraps
`whisper_vad_context`, loaded from the bundled VAD model at startup
alongside the ASR model. `feed()` calls
`whisper_vad_detect_speech_no_reset()`; `segments()` calls
`whisper_vad_segments_from_probs()` with `min_silence_duration_ms` (and
whisper.cpp's other defaults — `threshold=0.5`, `speech_pad_ms=30` to avoid
clipping word edges, `min_speech_duration_ms=250` to reject spurious blips)
sourced from `whisper_vad_default_params()`. JNI additions mirror the
pattern in `whisper_jni.cpp`; iOS additions mirror `WhisperEngine.ios.kt`.

**Implementation risk to verify early — CONFIRMED FALSE, see "Correction (2026-08-26)" above.**
(spike before building the full
pipeline on top of it): confirm on-device, with a short real recording, that
segments only appear via `segments()` once actually closed by trailing
silence — not partially/speculatively while still open. The `whisper.cpp`
example (`examples/vad-speech-segments`) only demonstrates fully-offline,
whole-file VAD (`whisper_vad_detect_speech` over a complete buffer, once);
our streaming use of `_detect_speech_no_reset` + repeated `segments()` calls
during incremental feeding is not directly demonstrated upstream, so this
must be confirmed rather than assumed.

**`SpeechSegmenter`** (new, `commonMain`, replaces `PauseDetector` and the
segmentation half of `TranscriptionSession`): owns the raw-sample buffer for
the current recording, feeds chunks to a `VoiceActivityDetector`, and emits
newly-closed segments as they're found:

```kotlin
class SpeechSegmenter(
    private val vad: VoiceActivityDetector,
    private val sampleRateHz: Int,
    private val minSilenceDurationMs: Int = 500
) {
    // Feed one capture chunk. Returns any segment(s) that just closed.
    fun accept(samples: FloatArray): List<FloatArray>
    // Force-finalizes whatever's still open (end of recording, no pause
    // reached). Returns null if nothing pending.
    fun flush(): FloatArray?
}
```

`minSilenceDurationMs` defaults to 500ms here (not the RMS path's 1500ms) —
VAD's speech/silence discrimination is precise enough that a shorter gap is
safe and cuts perceived latency; exact value is a tuning knob to confirm
on-device, not a hard requirement.

**`TranscriptionSession`** shrinks to just the transcribe stage: receives
finalized segments (from `SpeechSegmenter` via `segmentChannel`), calls
`transcribe()`, collects results. It no longer owns pause detection.

**`App.kt`** wiring: adds `segmentChannel: Channel<FloatArray>` and a second
coroutine between the existing capture callback and the existing transcribe
consumer, following the same non-blocking `trySend`/unlimited-channel
pattern already used for `audioChannel` today.

**Model bundling**: `ModelPathProvider` gains a second resolution method
(e.g. `resolveVadModelPath()`) alongside the existing `resolveModelPath()`,
following the same bundled-asset pattern as `ggml-small.bin` today
(Android: `assets/models/`; iOS: app bundle resource).

## Removed

- `PauseDetector` and its RMS-threshold math — fully replaced by VAD.
- The "skip transcribe if RMS never crossed threshold" hallucination guard
  in `TranscriptionSession.finalizeSegment()` — structurally unnecessary
  once segments only come from `SpeechSegmenter`, since VAD only emits a
  segment when it actually found speech in it. A purely-silent buffer never
  reaches the Transcriber stage at all.

## Error Handling & Edge Cases

**Handled:**

- **VAD model load failure**: same pattern as today's Whisper model load
  failure — `runCatching` around session construction, `UiState.Error` if it
  fails. No fallback to RMS; the VAD model is bundled and as reliable as the
  ASR model already required.
- **Stop mid-utterance**: `SpeechSegmenter.flush()` forces out the
  still-open trailing segment (mirrors today's
  `TranscriptionSession.stop()` force-finalize of `pendingSamples`), then
  `segmentChannel` closes and the Transcriber drains whatever's left before
  returning — same drain-and-join pattern as today's `audioChannel`, one hop
  further out.
- **Backgrounded mid-recording**: unaffected — `AppLifecycleObserver` still
  calls `stopRecording()`, which now also has to await the Segmenter
  draining `audioChannel` before the Transcriber drains `segmentChannel`.

**Explicitly out of scope:**

- Recovering audio if the VAD native call itself throws/crashes mid-session
  — treated as fatal, same severity as a `transcribe()` failure today (no
  existing retry/recovery for that either).
- Tuning `threshold`/`min_speech_duration_ms`/`speech_pad_ms` beyond
  whisper.cpp's defaults — only `min_silence_duration_ms` is called out
  above as needing a different default than upstream's.

## Testing & Success Criteria

- `SpeechSegmenter`'s policy (buffer bookkeeping, emitting closed segments,
  `flush()` behavior) is unit-tested against a fake `VoiceActivityDetector`
  returning canned segment lists — same style as today's
  `PauseDetectorTest`/`TranscriptionSessionTest`, no real model needed.
- `WhisperVad`'s native wrapper (JNI/cinterop) is not unit-testable without
  the real model — verified manually on-device, re-running the same
  logcat-driven repro used to diagnose this issue, comparing before/after:
  segment boundaries should land at actual pauses (no mid-word "..."
  truncations), and the "Stopping…" state should resolve close to real-time
  rather than 10+ seconds after Stop is tapped.

**Success criteria**: recording the same test utterance used in the
Evidence section no longer produces mid-clause splits or a multi-second
post-Stop delay before the full transcript appears, and Whisper's segment
boundaries become subjectively comparable to the native engine's.
