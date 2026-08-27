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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext

enum class AsrEngine { WHISPER, NATIVE }

sealed interface UiState {
    data object Idle : UiState
    // Synchronous gate between a Record tap and startRecording() actually setting Recording -
    // covers the permission-check/request suspension, which can take arbitrarily long (a full
    // system dialog). Without a distinct state here, uiState reads Idle for that whole window,
    // so a second rapid tap would see Idle too and launch a second concurrent pipeline.
    data object Starting : UiState
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

// Carries the time a segment was handed off to segmentChannel, so the consumer side can log
// how long it sat in the queue before transcribe() picked it up - the direct signal for whether
// the pipeline is falling behind live speech (see consumerJob below).
private class TimedSegment(val samples: FloatArray, val enqueuedAtMs: Long)

// One line of the on-screen transcript history: either a recognized segment (engine marker +
// text) or a per-recording metrics summary. Kept as a sealed type instead of a plain String so
// the two can be styled differently - the marker/summary are visual noise next to the actual
// recognized text and are greyed out accordingly, see the rendering in App() below.
private sealed interface TranscriptEntry {
    data class Segment(val engineLabel: String, val text: String) : TranscriptEntry
    data class Metrics(val summary: String) : TranscriptEntry
}

@Composable
fun App(
    audioCapture: AudioCapture,
    micPermission: MicPermission,
    modelPathProvider: ModelPathProvider,
    nativeAsr: NativeAsrEngine? = null
) {
    var uiState by remember { mutableStateOf<UiState>(UiState.Idle) }
    var transcript by remember { mutableStateOf(listOf<TranscriptEntry>()) }
    // SupervisorJob so one child coroutine throwing (e.g. AudioCapture.start() on a busy mic,
    // or a native transcribe()/speechProbability() call) can't cancel its siblings or the scope
    // itself - a plain Job would propagate the failure upward and permanently kill every future
    // scope.launch{} for the rest of the app's life, since a cancelled scope rejects new
    // coroutines immediately. The handler surfaces the failure instead of leaving it silently
    // swallowed (a coroutine's default behavior with no other handler installed).
    val scope = remember {
        CoroutineScope(
            Dispatchers.Default + SupervisorJob() + CoroutineExceptionHandler { _, throwable ->
                debugLog("App: uncaught coroutine exception: $throwable")
                uiState = UiState.Error(throwable.message ?: "Unexpected error")
            }
        )
    }
    // Dispatchers.IO isn't accessible from commonMain (JVM/Native-only, internal here) - a
    // dedicated single thread, entirely separate from Default's shared CPU-bound pool, gives
    // the long-running blocking transcribe() call somewhere to run without starving Default's
    // limited threads out from under segmenterJob. See consumerJob below for why this matters.
    @OptIn(DelicateCoroutinesApi::class)
    val transcribeDispatcher = remember { newSingleThreadContext("WhisperTranscribe") }
    // Tracks a stopRecording() call in flight, so the DisposableEffect below can wait for it
    // to actually finish instead of cancelling it out from under itself - see its onDispose.
    var stopRecordingJob by remember { mutableStateOf<Job?>(null) }
    var selectedEngine by remember { mutableStateOf(AsrEngine.WHISPER) }
    var whisperSegmentsShown by remember { mutableStateOf(0) }
    val nativeAvailable = remember(nativeAsr) { nativeAsr?.isAvailable() ?: false }
    KeepScreenOn(enabled = uiState == UiState.Recording)

    // Loading either model can fail (missing/corrupt bundled file); pipeline stays null
    // and an error state is shown instead of letting the app crash on first use.
    val pipeline = remember {
        runCatching {
            val loadStartMs = nowMs()
            val engine = WhisperEngine(modelPathProvider.resolveModelPath())
            // If WhisperVad's init throws, engine is already holding a native context - release
            // it here rather than letting it leak, since pipeline (and the DisposableEffect that
            // would otherwise release it) never gets constructed in that case.
            val vad = try {
                WhisperVad(modelPathProvider.resolveVadModelPath())
            } catch (e: Throwable) {
                engine.release()
                throw e
            }
            debugLog("App: Whisper model + VAD load time=${nowMs() - loadStartMs}ms")
            WhisperPipeline(
                TranscriptionSession(engine, sampleRateHz = 16_000),
                SpeechSegmenter(vad, sampleRateHz = 16_000),
                engine,
                vad
            )
        }.onFailure { e ->
            uiState = UiState.Error(e.message ?: "Failed to load speech model")
        }.getOrNull()
    }
    DisposableEffect(pipeline) {
        onDispose {
            val pendingStop = stopRecordingJob
            if (pendingStop != null && !pendingStop.isCompleted) {
                // A stopRecording() call is already in flight on `scope` (e.g. an Android
                // rotation fired onPause -> onBackground moments before this runs). Cancelling
                // scope now would kill that coroutine at whatever suspension point it's at,
                // freeze uiState at Stopping forever (it only reaches Idle at the very end of
                // stopRecording()), and make the check below always skip release - a guaranteed
                // leak on every single rotation-while-recording, not just a rare race. Instead,
                // let it actually finish - its own join()s on segmenterJob/consumerJob already
                // wait out anything in-flight - then release once nothing can be mid a
                // synchronous native call, and only then cancel scope.
                scope.launch {
                    pendingStop.join()
                    pipeline?.release()
                    transcribeDispatcher.close()
                    scope.cancel()
                }
            } else if (uiState != UiState.Recording) {
                // No stop in flight. Recording with no pending stop (backgrounded via some path
                // that bypasses both the button and the lifecycle observer) is the one remaining
                // case where a synchronous native call could genuinely be in progress with no
                // way to wait it out from here - leak rather than risk a use-after-free, same
                // tradeoff as before. Every other state (Idle, Starting, PermissionDenied,
                // Error, or Stopping that already completed) is safe to release immediately.
                scope.cancel()
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
    var segmentChannel by remember { mutableStateOf<Channel<TimedSegment>?>(null) }
    var segmenterJob by remember { mutableStateOf<Job?>(null) }
    var consumerJob by remember { mutableStateOf<Job?>(null) }
    var nativeStartJob by remember { mutableStateOf<Job?>(null) }
    // Timestamp of the Record tap, for the end-to-end and time-to-first-segment metrics below.
    var recordStartMs by remember { mutableStateOf(0L) }
    var timeToFirstSegmentMs by remember { mutableStateOf<Long?>(null) }
    var segmentMetrics by remember { mutableStateOf<List<SegmentMetrics>>(emptyList()) }
    var segmentBacklogsMs by remember { mutableStateOf<List<Long>>(emptyList()) }

    fun startRecording() {
        recordStartMs = nowMs()
        timeToFirstSegmentMs = null
        segmentMetrics = emptyList()
        segmentBacklogsMs = emptyList()
        debugLog("App.startRecording: entered, engine=$selectedEngine")
        when (selectedEngine) {
            AsrEngine.WHISPER -> {
                val activePipeline = pipeline ?: return
                uiState = UiState.Recording

                val audio = Channel<FloatArray>(Channel.UNLIMITED)
                audioChannel = audio
                val segments = Channel<TimedSegment>(Channel.UNLIMITED)
                segmentChannel = segments

                // Stage 1: real-time segmenter. Only does VAD-driven boundary detection -
                // never calls transcribe() - so it can't fall behind no matter how slow
                // transcription is. This is what fixes segment boundaries being computed
                // against a stale backlog (see the design spec's Evidence section).
                segmenterJob = scope.launch {
                    // segments.close() must run even if accept()/flush() throws - otherwise
                    // consumerJob's `for (timedSegment in segments)` below waits on a channel
                    // that will never close, and stopRecording()'s consumerJob?.join() hangs
                    // forever with uiState stuck at Stopping.
                    try {
                        for (samples in audio) {
                            activePipeline.segmenter.accept(samples).forEach { segment ->
                                val result = segments.trySend(TimedSegment(segment, nowMs()))
                                if (result.isFailure) {
                                    debugLog("App: segmentChannel.trySend failed (channel closed?): $result")
                                }
                            }
                        }
                        activePipeline.segmenter.flush()?.let { segment ->
                            segments.trySend(TimedSegment(segment, nowMs()))
                        }
                    } finally {
                        segments.close()
                        debugLog("App: segmenter loop exiting (audioChannel closed)")
                    }
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
                var firstSegmentLogged = false
                consumerJob = scope.launch(transcribeDispatcher) {
                    for (timedSegment in segments) {
                        val backlogMs = nowMs() - timedSegment.enqueuedAtMs
                        debugLog("App: dequeued segment after ${backlogMs}ms in segmentChannel")
                        segmentBacklogsMs = segmentBacklogsMs + backlogMs
                        val metrics = activePipeline.session.transcribeSegment(timedSegment.samples)
                        segmentMetrics = segmentMetrics + metrics
                        val allSegments = activePipeline.session.segments
                        if (allSegments.size > whisperSegmentsShown) {
                            if (!firstSegmentLogged) {
                                timeToFirstSegmentMs = nowMs() - recordStartMs
                                debugLog("App: time-to-first-segment=${timeToFirstSegmentMs}ms")
                                firstSegmentLogged = true
                            }
                            transcript = transcript + allSegments.subList(whisperSegmentsShown, allSegments.size)
                                .map { TranscriptEntry.Segment("Whisper", it) }
                            whisperSegmentsShown = allSegments.size
                        }
                    }
                    debugLog("App: transcribe consumer loop exiting (segmentChannel closed)")
                }

                audioCapture.start { samples ->
                    val resampled = resampleTo16k(samples, audioCapture.sampleRateHz)
                    // resampleTo16k returns an empty array for a degenerate/invalid source rate
                    // (see its own guard) rather than throwing on this non-coroutine callback
                    // thread - skip enqueueing it instead of letting an empty buffer reach the
                    // segmenter/VAD.
                    if (resampled.isNotEmpty()) {
                        val result = audio.trySend(resampled)
                        if (result.isFailure) {
                            debugLog("App: audioChannel.trySend failed (channel closed?): $result")
                        }
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
                            onSegment = { text -> transcript = transcript + TranscriptEntry.Segment("Native", text) },
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
                // Must read/flush segments before session.stop() below, which clears them.
                val finalSegments = activePipeline.session.segments
                if (finalSegments.size > whisperSegmentsShown) {
                    transcript = transcript + finalSegments.subList(whisperSegmentsShown, finalSegments.size)
                        .map { TranscriptEntry.Segment("Whisper", it) }
                }
                activePipeline.session.stop()
                // pipeline is reused (remember{}'d) across multiple Record/Stop cycles in the
                // same app session, so the segmenter's buffer/VAD state must be cleared here -
                // otherwise buffer grows unboundedly across recordings and the next recording's
                // VAD trace starts contaminated by this one's tail, mirroring why session.stop()
                // above resets the transcriber's context and finalizedSegments.
                activePipeline.segmenter.reset()
                // finalizedSegments was just cleared by session.stop() above, so the next
                // recording's segments start renumbering from zero too.
                whisperSegmentsShown = 0
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
        val endToEndMs = nowMs() - recordStartMs
        debugLog("App.stopRecording: end-to-end time=${endToEndMs}ms")
        val summary = when (selectedEngine) {
            AsrEngine.WHISPER -> {
                val firstSegmentText = timeToFirstSegmentMs?.let { "${it}ms" } ?: "n/a"
                val avgRtf = if (segmentMetrics.isNotEmpty()) roundTo2(segmentMetrics.map { it.rtf }.average()) else 0.0
                val maxRtf = segmentMetrics.maxOfOrNull { it.rtf } ?: 0.0
                val maxBacklogMs = segmentBacklogsMs.maxOrNull() ?: 0L
                "End-to-end: ${endToEndMs}ms | First segment: $firstSegmentText | " +
                    "Segments: ${segmentMetrics.size} (avg RTF $avgRtf, max $maxRtf) | Max backlog: ${maxBacklogMs}ms"
            }
            AsrEngine.NATIVE -> "End-to-end: ${endToEndMs}ms"
        }
        // Appended to the transcript history (not a separate transient field) so it survives
        // into the next recording instead of disappearing the moment Record is tapped again.
        transcript = transcript + TranscriptEntry.Metrics(summary)
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
                stopRecordingJob = scope.launch { stopRecording() }
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
                stopRecordingJob = scope.launch {
                    debugLog("App: Stop click -> launching stopRecording()")
                    stopRecording()
                    debugLog("App: stopRecording() coroutine completed")
                }
            }
            UiState.Idle -> {
                // Mirrors the Stop branch's synchronous gate above: flip uiState before
                // launching so a second rapid tap - arriving anywhere in the permission
                // check/request window below, which can suspend for an entire system dialog -
                // sees Starting instead of Idle and falls through to the no-op branch, instead
                // of also matching UiState.Idle and starting a second concurrent pipeline.
                uiState = UiState.Starting
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
            UiState.Starting, UiState.Stopping, UiState.PermissionDenied, is UiState.Error -> Unit
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
                            transcript.forEach { entry ->
                                when (entry) {
                                    is TranscriptEntry.Segment -> Text(
                                        buildAnnotatedString {
                                            withStyle(SpanStyle(color = Color.Gray)) {
                                                append("[${entry.engineLabel}] ")
                                            }
                                            append(entry.text)
                                        }
                                    )
                                    is TranscriptEntry.Metrics -> Text(
                                        entry.summary,
                                        color = Color.Gray,
                                        fontSize = 12.sp
                                    )
                                }
                            }
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
                                UiState.Starting -> "Starting…"
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