package ai.healthcarepoc.voice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext

enum class AsrEngine { WHISPER, NATIVE }

sealed interface UiState {
    data object Idle : UiState
    data object Recording : UiState
    data object Stopping : UiState
    data object PermissionDenied : UiState
    data class Error(val message: String) : UiState
}

@Composable
private fun EngineToggleButton(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick, enabled = enabled) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled) { Text(label) }
    }
}

// Bundles the two Whisper-path components that must be constructed together (and fail
// together) at session start: the segmenter (VAD-driven, real-time) and the
// transcription session (slow, decoupled via segmentChannel - see startRecording()).
// Retains native handles (engine, vad) so they can be released when the pipeline is disposed.
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

@Composable
fun App(
    audioCapture: AudioCapture,
    micPermission: MicPermission,
    modelPathProvider: ModelPathProvider,
    nativeSampleRateHz: () -> Int,
    nativeAsr: NativeAsrEngine? = null
) {
    var uiState by remember { mutableStateOf<UiState>(UiState.Idle) }
    var transcript by remember { mutableStateOf(listOf<String>()) }
    val scope = remember { CoroutineScope(Dispatchers.Default) }
    // Dispatchers.IO isn't accessible from commonMain (JVM/Native-only, internal here) - a
    // dedicated single thread, entirely separate from Default's shared CPU-bound pool, gives
    // the long-running blocking transcribe() call somewhere to run without starving Default's
    // limited threads out from under segmenterJob. See consumerJob below for why this matters.
    @OptIn(DelicateCoroutinesApi::class)
    val transcribeDispatcher = remember { newSingleThreadContext("WhisperTranscribe") }
    var selectedEngine by remember { mutableStateOf(AsrEngine.WHISPER) }
    var whisperSegmentsShown by remember { mutableStateOf(0) }
    val nativeAvailable = remember(nativeAsr) { nativeAsr?.isAvailable() ?: false }

    // Loading either model can fail (missing/corrupt bundled file); pipeline stays null
    // and an error state is shown instead of letting the app crash on first use.
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
        onDispose {
            scope.cancel()
            // If disposed while a recording is in flight (e.g. a config change happens exactly
            // mid-recording), skip releasing the native handles here rather than risk a
            // use-after-free against a still-running scope.launch{} coroutine that's mid-call
            // into transcribe()/speechProbability() - those are synchronous native calls, not
            // suspend functions, so cancelling scope cannot interrupt one already in progress.
            // This leaks the handles in that rare case instead of crashing; ordinary disposal
            // while idle (the common case) still releases correctly.
            // transcribeDispatcher.close() is gated the same way: closing it while consumerJob
            // is still running on it (e.g. mid in-flight transcribe() call) risks the coroutine
            // hitting a rejected-execution error trying to continue on a closed dispatcher once
            // that call returns - same leak-over-crash tradeoff as the handle release above.
            if (uiState != UiState.Recording && uiState != UiState.Stopping) {
                pipeline?.release()
                transcribeDispatcher.close()
            }
        }
    }

    // Decouples audio capture from transcription. The capture thread (AudioCapture's reader
    // loop) must never block: WhisperEngine.transcribe() is a slow native call (many seconds),
    // and if the capture thread waited on it directly, AudioRecord's buffer overflows and
    // every word spoken while transcription is running gets silently dropped by the OS -
    // confirmed by logs showing 0 buffers captured for the remainder of a session after the
    // first pause-triggered transcribe() call started. The capture callback below only ever
    // enqueues samples (fast, non-blocking); a separate consumer coroutine drains the queue
    // and performs acceptAudio()/transcribe() sequentially, off the capture thread.
    var audioChannel by remember { mutableStateOf<Channel<FloatArray>?>(null) }
    var segmentChannel by remember { mutableStateOf<Channel<FloatArray>?>(null) }
    var segmenterJob by remember { mutableStateOf<Job?>(null) }
    var consumerJob by remember { mutableStateOf<Job?>(null) }
    var nativeStartJob by remember { mutableStateOf<Job?>(null) }

    fun startRecording() {
        debugLog("App.startRecording: entered, engine=$selectedEngine")
        when (selectedEngine) {
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
                // Dispatched on transcribeDispatcher (its own dedicated thread), not the shared
                // scope's Default: transcribe() is a long-running blocking native call (not a
                // suspend function, so it never yields), and running it on Default's small
                // CPU-bound pool would starve segmenterJob out of that same pool while a
                // transcribe() call is in flight - reintroducing exactly the decode-timing
                // coupling this two-stage pipeline exists to avoid, just via thread contention
                // instead of a shared queue. Confirmed on-device: segments recorded while no
                // transcribe() was in flight came out as one clean multi-second chunk; segments
                // recorded while one was running got chopped to under a second, with visibly bursty
                // (non-100ms-steady) chunk timestamps - and Whisper hallucinated "[MUSIK]"/"[P]" on
                // the resulting short fragments.
                consumerJob = scope.launch(transcribeDispatcher) {
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
            AsrEngine.NATIVE -> {
                val engine = nativeAsr ?: return
                uiState = UiState.Recording
                nativeStartJob = scope.launch {
                    runCatching {
                        engine.start(
                            onSegment = { text -> transcript = transcript + "[Native] $text" },
                            onError = { message -> uiState = UiState.Error(message) }
                        )
                    }.onFailure { e ->
                        uiState = UiState.Error(e.message ?: "Native recognizer failed to start")
                    }
                }
                debugLog("App.startRecording: nativeAsr.start() launched, uiState=Recording")
            }
        }
    }

    suspend fun stopRecording() {
        debugLog("App.stopRecording: entered, engine=$selectedEngine")
        when (selectedEngine) {
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
                // pipeline is reused (remember{}'d) across multiple Record/Stop cycles in the
                // same app session, so the segmenter's buffer/VAD state must be cleared here -
                // otherwise buffer grows unboundedly across recordings and the next recording's
                // VAD trace starts contaminated by this one's tail, mirroring why session.stop()
                // above resets the transcriber's context.
                activePipeline.segmenter.reset()
                val finalSegments = activePipeline.session.segments
                if (finalSegments.size > whisperSegmentsShown) {
                    transcript = transcript + finalSegments.subList(whisperSegmentsShown, finalSegments.size)
                        .map { "[Whisper] $it" }
                    whisperSegmentsShown = finalSegments.size
                }
                debugLog("App.stopRecording: session.stop() returned after ${nowMs() - t2}ms, segments=${finalSegments.size}")
            }
            AsrEngine.NATIVE -> {
                val engine = nativeAsr ?: return
                nativeStartJob?.join()
                nativeStartJob = null
                val t0 = nowMs()
                engine.stop()
                debugLog("App.stopRecording: nativeAsr.stop() returned after ${nowMs() - t0}ms")
            }
        }
        uiState = UiState.Idle
        debugLog("App.stopRecording: uiState=Idle")
    }

    // The design doc requires recording to stop automatically if the app is backgrounded.
    val lifecycleObserver = remember {
        AppLifecycleObserver(onBackground = {
            // Runs off the main/UI thread for the same reason as the button path above:
            // stopRecording() -> TranscriptionSession.stop() calls the blocking native
            // WhisperEngine.transcribe(), and onBackground fires on the main thread on
            // both platforms (Android onPause / iOS mainQueue), so calling it directly
            // would ANR the app when backgrounded mid-recording.
            if (uiState == UiState.Recording) {
                // Same synchronous gate as onRecordButtonClick(): flip the state before
                // launching so a Stop tap arriving around the same moment (e.g. the user
                // backgrounds the app and taps Stop right as it resumes) can't also see
                // uiState==Recording and launch its own concurrent stopRecording().
                uiState = UiState.Stopping
                scope.launch { stopRecording() }
            }
        })
    }
    DisposableEffect(Unit) {
        lifecycleObserver.start()
        onDispose { lifecycleObserver.stop() }
    }

    fun onRecordButtonClick() {
        debugLog("App: button clicked, uiState=$uiState")
        when (uiState) {
            UiState.Recording -> {
                // Flip the gate synchronously, before launching the coroutine: stopRecording()
                // only sets uiState back to Idle after its (possibly many-second) native
                // transcribe() call returns, so a second rapid tap on Stop while that's in
                // flight would otherwise still see uiState==Recording and launch a second,
                // concurrent stopRecording() - which races TranscriptionSession.stop() (no
                // internal synchronization) and can fire two overlapping calls into the same
                // non-reentrant native whisper_context, crashing the process.
                uiState = UiState.Stopping
                scope.launch {
                    debugLog("App: Stop click -> launching stopRecording()")
                    stopRecording()
                    debugLog("App: stopRecording() coroutine completed")
                }
            }
            UiState.Idle -> {
                scope.launch {
                    val status = micPermission.status()
                    debugLog("App: Record click -> micPermission.status()=$status")
                    when (status) {
                        PermissionStatus.GRANTED -> startRecording()
                        PermissionStatus.DENIED -> {
                            val requested = micPermission.request()
                            debugLog("App: micPermission.request()=$requested")
                            when (requested) {
                                PermissionStatus.GRANTED -> startRecording()
                                PermissionStatus.DENIED -> uiState = UiState.PermissionDenied
                            }
                        }
                    }
                }
            }
            UiState.Stopping, UiState.PermissionDenied, is UiState.Error -> Unit
        }
    }

    MaterialTheme {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (val state = uiState) {
                    is UiState.PermissionDenied -> {
                        Column {
                            Text("Microphone permission is required to record.")
                            Button(onClick = { micPermission.openAppSettings() }) {
                                Text("Open Settings")
                            }
                        }
                    }
                    is UiState.Error -> {
                        Text("Error: ${state.message}")
                    }
                    else -> {
                        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            transcript.forEach { segment -> Text(segment) }
                        }
                    }
                }
            }

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
                    Button(
                        onClick = { onRecordButtonClick() },
                        modifier = Modifier.size(120.dp),
                        shape = CircleShape,
                        contentPadding = ButtonDefaults.TextButtonContentPadding
                    ) {
                        Text(
                            when (uiState) {
                                UiState.Recording -> "Stop"
                                UiState.Stopping -> "Stopping…"
                                else -> "Record"
                            },
                            fontSize = 20.sp
                        )
                    }
                }
            }
        }
    }
}