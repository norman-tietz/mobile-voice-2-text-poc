# Native ASR Comparison Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the user switch between the existing Whisper engine and Android's on-device `SpeechRecognizer` from the recording screen, with each transcript segment labeled by which engine produced it, and fix a layout bug where transcript text can be hidden under system bars on edge-to-edge-enforced Android versions.

**Architecture:** Add a new `NativeAsrEngine` interface (`commonMain`) implemented only on Android (`AndroidSpeechRecognizerEngine`, `androidMain`), since `SpeechRecognizer` is a push-model API (owns the mic, does its own endpointing) that can't plug into the existing pull-model `AudioCapture`/`PauseDetector`/`TranscriptionSession` pipeline. `App.kt` gains an engine toggle and branches `startRecording`/`stopRecording` on the selected engine; the toggle is hidden on iOS via a nullable, defaulted constructor parameter, so no iOS file needs to change.

**Tech Stack:** Kotlin Multiplatform 2.4.10, Compose Multiplatform 1.11.1, Android `SpeechRecognizer`/`RecognizerIntent` (`android.speech`), Kotlin coroutines (`kotlinx.coroutines`).

**Spec:** `docs/superpowers/specs/2026-08-24-native-asr-comparison-design.md`

## Global Constraints

- On-device only, no cloud fallback: gate on `SpeechRecognizer.isOnDeviceRecognitionAvailable(context)` and create via `SpeechRecognizer.createOnDeviceSpeechRecognizer(context)` — never `createSpeechRecognizer`/`EXTRA_PREFER_OFFLINE`, which only *prefer* on-device and can silently fall back to cloud.
- Both of the above APIs require API 31 (`Build.VERSION_CODES.S`); the app's `minSdk` is 26, so every call site must be guarded by `Build.VERSION.SDK_INT >= Build.VERSION_CODES.S`.
- Recognition language is `"de-DE"` (`RecognizerIntent.EXTRA_LANGUAGE`), matching the Whisper path's German-only scope (`WhisperEngine.ios.kt` hardcodes `language = "de"`).
- `debugLog` calls must never include recognized text or raw audio — control flow, counts, timings, and error codes only. This is a pre-existing hard constraint (see `DebugLog.kt`), and it applies to all new code in this plan.
- No iOS native engine is added; the engine toggle must not appear on iOS.
- No new automated tests for `SpeechRecognizer` integration — it has no fake-able seam (same situation as `WhisperEngine`'s native binding today). Verification is compile checks plus manual, on-device testing.

---

### Task 1: Fix edge-to-edge layout clipping in `App.kt`

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/ai/healthcarepoc/voice/App.kt:3-9` (imports), `App.kt:184` (root `Column` modifier)

**Interfaces:**
- Consumes: nothing new.
- Produces: nothing new (layout-only fix).

The app targets SDK 36; Android 15+ (API 35+) enforces edge-to-edge display at this target SDK with no opt-out. The root `Column`'s `Modifier.fillMaxSize().padding(16.dp)` uses a fixed padding, not real system-bar insets, so text and the record button can render partially under the status bar or gesture nav bar.

- [ ] **Step 1: Add the `safeDrawingPadding` import**

In `App.kt`, the import block currently reads (lines 3-9):

```kotlin
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
```

Add one import, keeping alphabetical order:

```kotlin
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
```

- [ ] **Step 2: Apply the modifier to the root `Column`**

Find (around line 183-186):

```kotlin
    MaterialTheme {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
```

Replace with:

```kotlin
    MaterialTheme {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
```

`safeDrawingPadding()` is Compose Multiplatform's cross-platform safe-area API — it resolves to real system bar/cutout/IME insets on Android and is a no-op where no such insets apply.

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :composeApp:compileDebugKotlinAndroid`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Manually verify on a device/emulator**

Run: `./gradlew :composeApp:installDebug` with an Android 15+ (API 35+) emulator or device connected, then launch the app.
Expected: the transcript area and the Record button are fully visible, not clipped by the status bar or gesture navigation bar.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/ai/healthcarepoc/voice/App.kt
git commit -m "fix: keep transcript and controls clear of system bars on edge-to-edge Android"
```

---

### Task 2: Add the `NativeAsrEngine` interface

**Files:**
- Create: `composeApp/src/commonMain/kotlin/ai/healthcarepoc/voice/NativeAsrEngine.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `NativeAsrEngine` — implemented by Task 3, consumed by Task 4's `App(nativeAsr: NativeAsrEngine? = null, ...)` parameter.
  ```kotlin
  interface NativeAsrEngine {
      fun isAvailable(): Boolean
      suspend fun start(onSegment: (String) -> Unit, onError: (String) -> Unit)
      suspend fun stop()
  }
  ```

This is a plain `commonMain` interface, not `expect`/`actual` — only Android implements it, and `App.kt` treats it as an optional (nullable) dependency rather than requiring every platform to provide one.

- [ ] **Step 1: Create the file**

```kotlin
package ai.healthcarepoc.voice

interface NativeAsrEngine {
    fun isAvailable(): Boolean
    suspend fun start(onSegment: (String) -> Unit, onError: (String) -> Unit)
    suspend fun stop()
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :composeApp:compileDebugKotlinAndroid`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/commonMain/kotlin/ai/healthcarepoc/voice/NativeAsrEngine.kt
git commit -m "feat: add NativeAsrEngine interface for a switchable native ASR path"
```

---

### Task 3: Implement `AndroidSpeechRecognizerEngine`

**Files:**
- Create: `composeApp/src/androidMain/kotlin/ai/healthcarepoc/voice/AndroidSpeechRecognizerEngine.kt`

**Interfaces:**
- Consumes: `NativeAsrEngine` (Task 2); `debugLog(message: String)` from `DebugLog.kt`.
- Produces: `AndroidSpeechRecognizerEngine(context: Context) : NativeAsrEngine` — constructed in Task 5's `MainActivity.kt`.

`SpeechRecognizer` is a push-model API: it owns the mic and does its own end-of-speech detection, delivering results via `RecognitionListener` callbacks on the same thread `startListening` was called from. All calls here happen on `Dispatchers.Main` so callbacks land there too. `onResults` restarts listening automatically (continuing the session segment-by-segment) unless a `stop()` is in flight, in which case it resolves the pending `stop()` call instead.

- [ ] **Step 1: Create the file**

```kotlin
package ai.healthcarepoc.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidSpeechRecognizerEngine(private val context: Context) : NativeAsrEngine {

    private var recognizer: SpeechRecognizer? = null
    private var active = false
    private var onSegment: ((String) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null
    private var stopSignal: CompletableDeferred<Unit>? = null

    override fun isAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    override suspend fun start(onSegment: (String) -> Unit, onError: (String) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            debugLog("AndroidSpeechRecognizerEngine.start: on-device recognition requires API 31+, aborting")
            onError("On-device speech recognition requires Android 12 or later")
            return
        }
        withContext(Dispatchers.Main) {
            this@AndroidSpeechRecognizerEngine.onSegment = onSegment
            this@AndroidSpeechRecognizerEngine.onError = onError
            active = true
            val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            recognizer = r
            r.setRecognitionListener(listener)
            debugLog("AndroidSpeechRecognizerEngine.start: recognizer created, starting listening")
            r.startListening(buildIntent())
        }
    }

    override suspend fun stop() {
        val deferred = CompletableDeferred<Unit>()
        withContext(Dispatchers.Main) {
            if (!active) {
                debugLog("AndroidSpeechRecognizerEngine.stop: not active, no-op")
                deferred.complete(Unit)
            } else {
                stopSignal = deferred
                active = false
                debugLog("AndroidSpeechRecognizerEngine.stop: calling stopListening()")
                recognizer?.stopListening()
            }
        }
        deferred.await()
        withContext(Dispatchers.Main) {
            recognizer?.destroy()
            recognizer = null
            onSegment = null
            onError = null
        }
        debugLog("AndroidSpeechRecognizerEngine.stop: recognizer destroyed")
    }

    private fun buildIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "de-DE")
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }

    private val listener = object : RecognitionListener {
        override fun onResults(results: Bundle) {
            val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            debugLog("AndroidSpeechRecognizerEngine.onResults: active=$active, textLength=${text.length}")
            if (text.isNotEmpty()) {
                onSegment?.invoke(text)
            }
            val signal = stopSignal
            if (signal != null) {
                stopSignal = null
                signal.complete(Unit)
            } else if (active) {
                recognizer?.startListening(buildIntent())
            }
        }

        override fun onError(error: Int) {
            debugLog("AndroidSpeechRecognizerEngine.onError: code=$error, active=$active")
            val signal = stopSignal
            if (signal != null) {
                stopSignal = null
                signal.complete(Unit)
                return
            }
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    if (active) recognizer?.startListening(buildIntent())
                }
                else -> {
                    active = false
                    onError?.invoke(errorMessage(error))
                }
            }
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun errorMessage(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
        SpeechRecognizer.ERROR_CLIENT -> "Client side error"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
        SpeechRecognizer.ERROR_NETWORK -> "Network error"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
        SpeechRecognizer.ERROR_SERVER -> "Server error"
        else -> "Speech recognizer error ($error)"
    }
}
```

Notes for the implementer:
- `isAvailable()` and the `SDK_INT` guard in `start()` are both required: `isOnDeviceRecognitionAvailable` and `createOnDeviceSpeechRecognizer` don't exist on the framework before API 31 and throw `NoSuchMethodError` if called on an older device — the `minSdk` here is 26, so this must never run unguarded.
- `stop()` calling `stopListening()` (not `cancel()`) is deliberate: `stopListening()` still delivers a final `onResults` for whatever was captured, matching the Whisper path's force-transcribe-on-Stop behavior. `cancel()` would discard it.
- All `SpeechRecognizer` methods and the `RecognitionListener` callbacks run on the main thread (an Android platform requirement); `stop()`'s `CompletableDeferred` is what lets a caller on a background dispatcher `await` a result produced on the main thread.

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :composeApp:compileDebugKotlinAndroid`
Expected: `BUILD SUCCESSFUL` (no `NewApi` lint/compile errors — confirms the `SDK_INT` guards satisfy the API-31 call sites).

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/androidMain/kotlin/ai/healthcarepoc/voice/AndroidSpeechRecognizerEngine.kt
git commit -m "feat: implement Android on-device SpeechRecognizer as a NativeAsrEngine"
```

---

### Task 4: Wire engine selection and the toggle UI into `App.kt`

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/ai/healthcarepoc/voice/App.kt` (imports; `App` signature; new state; `startRecording`/`stopRecording`; UI)

**Interfaces:**
- Consumes: `NativeAsrEngine` (Task 2) — via new `nativeAsr: NativeAsrEngine? = null` parameter on `App`.
- Produces: `enum class AsrEngine { WHISPER, NATIVE }`, consumed by Task 5 only insofar as `MainActivity.kt` now passes a real `NativeAsrEngine` for the parameter this task adds.

This task assumes Task 1's edit is already applied (root `Column` already has `safeDrawingPadding()`).

- [ ] **Step 1: Add the `AsrEngine` enum and new imports**

Add one import (Material3's `OutlinedButton`, used by the toggle) next to the existing `Button`/`ButtonDefaults` imports:

```kotlin
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
```

Add the enum directly above `sealed interface UiState`:

```kotlin
enum class AsrEngine { WHISPER, NATIVE }

sealed interface UiState {
```

- [ ] **Step 2: Add the `nativeAsr` parameter and new state**

Change the `App` signature from:

```kotlin
@Composable
fun App(
    audioCapture: AudioCapture,
    micPermission: MicPermission,
    modelPathProvider: ModelPathProvider,
    nativeSampleRateHz: () -> Int
) {
```

to:

```kotlin
@Composable
fun App(
    audioCapture: AudioCapture,
    micPermission: MicPermission,
    modelPathProvider: ModelPathProvider,
    nativeSampleRateHz: () -> Int,
    nativeAsr: NativeAsrEngine? = null
) {
```

The default of `null` means iOS's `MainViewController.kt` needs no changes at all — the toggle only ever renders when a real engine is supplied, which only `MainActivity.kt` (Task 5) will do.

Immediately after the existing state declarations:

```kotlin
    var uiState by remember { mutableStateOf<UiState>(UiState.Idle) }
    var transcript by remember { mutableStateOf(listOf<String>()) }
    val scope = remember { CoroutineScope(Dispatchers.Default) }
```

add:

```kotlin
    var selectedEngine by remember { mutableStateOf(AsrEngine.WHISPER) }
    var whisperSegmentsShown by remember { mutableStateOf(0) }
    val nativeAvailable = remember(nativeAsr) { nativeAsr?.isAvailable() ?: false }
```

`whisperSegmentsShown` tracks how many of `TranscriptionSession`'s (Whisper-only) segments have already been copied into `transcript`, so switching engines mid-session doesn't wipe out segments the other engine already produced (both `startRecording`'s Whisper branch and `stopRecording`'s Whisper branch now append only the *new* segments instead of overwriting `transcript` with the full Whisper-only list).

- [ ] **Step 3: Rewrite `startRecording()`**

Replace the whole function:

```kotlin
    fun startRecording() {
        debugLog("App.startRecording: entered, session=${if (session == null) "null" else "ready"}")
        val activeSession = session ?: return
        uiState = UiState.Recording
        val channel = Channel<FloatArray>(Channel.UNLIMITED)
        audioChannel = channel
        consumerJob = scope.launch {
            for (samples in channel) {
                activeSession.acceptAudio(samples)
                transcript = activeSession.segments
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
    fun startRecording() {
        debugLog("App.startRecording: entered, engine=$selectedEngine")
        when (selectedEngine) {
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
            AsrEngine.NATIVE -> {
                val engine = nativeAsr ?: return
                uiState = UiState.Recording
                scope.launch {
                    engine.start(
                        onSegment = { text -> transcript = transcript + "[Native] $text" },
                        onError = { message -> uiState = UiState.Error(message) }
                    )
                }
                debugLog("App.startRecording: nativeAsr.start() launched, uiState=Recording")
            }
        }
    }
```

- [ ] **Step 4: Rewrite `stopRecording()`**

Replace the whole function:

```kotlin
    suspend fun stopRecording() {
        debugLog("App.stopRecording: entered")
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
        transcript = activeSession.stop()
        debugLog("App.stopRecording: activeSession.stop() returned after ${nowMs() - t2}ms, segments=${transcript.size}")
        uiState = UiState.Idle
        debugLog("App.stopRecording: uiState=Idle")
    }
```

with:

```kotlin
    suspend fun stopRecording() {
        debugLog("App.stopRecording: entered, engine=$selectedEngine")
        when (selectedEngine) {
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
            AsrEngine.NATIVE -> {
                val engine = nativeAsr ?: return
                val t0 = nowMs()
                engine.stop()
                debugLog("App.stopRecording: nativeAsr.stop() returned after ${nowMs() - t0}ms")
            }
        }
        uiState = UiState.Idle
        debugLog("App.stopRecording: uiState=Idle")
    }
```

- [ ] **Step 5: Add the toggle UI**

Add this private composable above `fun App(...)`:

```kotlin
@Composable
private fun EngineToggleButton(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick, enabled = enabled) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled) { Text(label) }
    }
}
```

Find the bottom button block:

```kotlin
            if (uiState !is UiState.PermissionDenied && uiState !is UiState.Error) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
```

Insert the toggle row immediately before that `Box`, still inside the same `if`:

```kotlin
            if (uiState !is UiState.PermissionDenied && uiState !is UiState.Error) {
                if (nativeAsr != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
                    ) {
                        val toggleEnabled = uiState == UiState.Idle
                        EngineToggleButton("Whisper", selectedEngine == AsrEngine.WHISPER, toggleEnabled) {
                            selectedEngine = AsrEngine.WHISPER
                        }
                        EngineToggleButton("Native", selectedEngine == AsrEngine.NATIVE, toggleEnabled && nativeAvailable) {
                            selectedEngine = AsrEngine.NATIVE
                        }
                    }
                }
                Box(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
```

This needs the `Row` composable, which isn't imported yet. The `foundation.layout` import block (after Task 1's edit) reads:

```kotlin
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
```

Insert `Row` after `Column` (keeping capitalized type imports grouped before the lowercase function imports, matching the existing order):

```kotlin
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
```

- [ ] **Step 6: Verify it compiles**

Run: `./gradlew :composeApp:compileDebugKotlinAndroid`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: Commit**

```bash
git add composeApp/src/commonMain/kotlin/ai/healthcarepoc/voice/App.kt
git commit -m "feat: add Whisper/Native engine toggle and branch recording on selected engine"
```

---

### Task 5: Wire `AndroidSpeechRecognizerEngine` into `MainActivity` and verify end-to-end

**Files:**
- Modify: `composeApp/src/androidMain/kotlin/ai/healthcarepoc/voice/MainActivity.kt`

**Interfaces:**
- Consumes: `AndroidSpeechRecognizerEngine` (Task 3), `App(..., nativeAsr: NativeAsrEngine? = null)` (Task 4).
- Produces: nothing further downstream.

- [ ] **Step 1: Pass the engine into `App`**

Replace:

```kotlin
        setContent {
            App(
                audioCapture = AudioCapture(),
                micPermission = MicPermission(this),
                modelPathProvider = ModelPathProvider(this),
                nativeSampleRateHz = { 16_000 }
            )
        }
```

with:

```kotlin
        setContent {
            App(
                audioCapture = AudioCapture(),
                micPermission = MicPermission(this),
                modelPathProvider = ModelPathProvider(this),
                nativeSampleRateHz = { 16_000 },
                nativeAsr = AndroidSpeechRecognizerEngine(this)
            )
        }
```

- [ ] **Step 2: Build and install the app**

Run: `./gradlew :composeApp:assembleDebug && ./gradlew :composeApp:installDebug` (Android device or emulator connected)
Expected: `BUILD SUCCESSFUL`, app installs.

- [ ] **Step 3: Manually verify the full feature**

On the installed app:

1. On an API 31+ device/emulator: confirm both "Whisper" and "Native" toggle buttons are visible and enabled while idle.
2. On a pre-API-31 device/emulator (or one without on-device recognition support), if available: confirm "Native" is disabled.
3. Select "Native", tap Record, speak a short German phrase with a pause, then another phrase, then tap Stop. Confirm each resulting transcript line is prefixed `[Native]`.
4. Select "Whisper", tap Record, speak, tap Stop. Confirm new lines are prefixed `[Whisper]` and the earlier `[Native]`-prefixed lines from step 3 are still visible above them.
5. While "Native" is selected and recording, background the app (Home button). Confirm recording stops automatically (same as the existing Whisper-mode behavior).
6. While recording in "Native" mode, tap Stop mid-utterance (before finishing a sentence). Confirm a final `[Native]` segment appears for whatever was captured, rather than nothing.
7. Confirm the toggle buttons are disabled (not tappable) while `Recording`/`Stopping`.

- [ ] **Step 4: Run the existing unit test suite to confirm no regressions**

Run: `./gradlew :composeApp:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL` — `PauseDetectorTest` and `TranscriptionSessionTest` still pass unchanged (this task doesn't touch either).

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/androidMain/kotlin/ai/healthcarepoc/voice/MainActivity.kt
git commit -m "feat: wire AndroidSpeechRecognizerEngine into MainActivity"
```

---

### Task 6: Document the engine toggle in the README

**Files:**
- Modify: `README.md`

**Interfaces:**
- Consumes: nothing (documentation only).
- Produces: nothing.

The README's "How it works" section currently describes only the Whisper pipeline as if it were the app's only path. After this change that's Android-specific — add a short note.

- [ ] **Step 1: Add a short paragraph after the existing "How it works" diagram/bullet list**

Find the end of the bullet list that starts with `- **AudioCapture**...` and ends with:

```markdown
Everything except the two native `WhisperEngine` bindings and the two
`AudioCapture` bindings is shared Kotlin in `commonMain`.
```

Add immediately after it:

```markdown

## Comparing against Android's native recognizer

On Android only, a toggle next to the Record button switches between the
Whisper pipeline above and Android's on-device `SpeechRecognizer`
(`AndroidSpeechRecognizerEngine`), gated on
`SpeechRecognizer.isOnDeviceRecognitionAvailable` (requires Android 12/API 31+)
so it never falls back to cloud-based recognition. This exists purely to
let the two engines' output be compared by ear on the same device — see
`docs/superpowers/specs/2026-08-24-native-asr-comparison-design.md`. Each
transcript line is prefixed with the engine that produced it
(`[Whisper]`/`[Native]`). The toggle doesn't appear on iOS.
```

- [ ] **Step 2: Commit**

```bash
git add README.md
git commit -m "docs: document the Whisper/Native engine comparison toggle"
```
