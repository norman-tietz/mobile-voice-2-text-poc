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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

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
    var selectedEngine by remember { mutableStateOf(AsrEngine.WHISPER) }
    var whisperSegmentsShown by remember { mutableStateOf(0) }
    val nativeAvailable = remember(nativeAsr) { nativeAsr?.isAvailable() ?: false }

    // Loading the model can fail (missing/corrupt bundled file); session stays null
    // and an error state is shown instead of letting the app crash on first use.
    val session = remember {
        runCatching {
            val engine = WhisperEngine(modelPathProvider.resolveModelPath())
            TranscriptionSession(engine, PauseDetector(sampleRateHz = 16_000))
        }.onFailure { e ->
            uiState = UiState.Error(e.message ?: "Failed to load speech model")
        }.getOrNull()
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
    var consumerJob by remember { mutableStateOf<Job?>(null) }
    var nativeStartJob by remember { mutableStateOf<Job?>(null) }

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