package ai.healthcarepoc.voice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

sealed interface UiState {
    data object Idle : UiState
    data object Recording : UiState
    data object PermissionDenied : UiState
    data class Error(val message: String) : UiState
}

@Composable
fun App(
    audioCapture: AudioCapture,
    micPermission: MicPermission,
    modelPathProvider: ModelPathProvider,
    nativeSampleRateHz: () -> Int
) {
    var uiState by remember { mutableStateOf<UiState>(UiState.Idle) }
    var transcript by remember { mutableStateOf(listOf<String>()) }
    val scope = remember { CoroutineScope(Dispatchers.Default) }

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

    fun startRecording() {
        val activeSession = session ?: return
        uiState = UiState.Recording
        audioCapture.start { samples ->
            val resampled = resampleTo16k(samples, nativeSampleRateHz())
            activeSession.acceptAudio(resampled)
            transcript = activeSession.segments
        }
    }

    fun stopRecording() {
        val activeSession = session ?: return
        audioCapture.stop()
        transcript = activeSession.stop()
        uiState = UiState.Idle
    }

    // The design doc requires recording to stop automatically if the app is backgrounded.
    val lifecycleObserver = remember {
        AppLifecycleObserver(onBackground = {
            // Runs off the main/UI thread for the same reason as the button path above:
            // stopRecording() -> TranscriptionSession.stop() calls the blocking native
            // WhisperEngine.transcribe(), and onBackground fires on the main thread on
            // both platforms (Android onPause / iOS mainQueue), so calling it directly
            // would ANR the app when backgrounded mid-recording.
            if (uiState == UiState.Recording) scope.launch { stopRecording() }
        })
    }
    DisposableEffect(Unit) {
        lifecycleObserver.start()
        onDispose { lifecycleObserver.stop() }
    }

    MaterialTheme {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (val state = uiState) {
                is UiState.PermissionDenied -> {
                    Text("Microphone permission is required to record.")
                    Button(onClick = { micPermission.openAppSettings() }) {
                        Text("Open Settings")
                    }
                }
                is UiState.Error -> {
                    Text("Error: ${state.message}")
                }
                else -> {
                    Button(onClick = {
                        if (uiState == UiState.Recording) {
                            // Runs off the main/UI thread (same as the startRecording() path
                            // below): stopRecording() -> TranscriptionSession.stop() calls the
                            // blocking native WhisperEngine.transcribe(), which can take many
                            // seconds and would otherwise ANR if invoked directly from onClick.
                            scope.launch { stopRecording() }
                        } else {
                            scope.launch {
                                when (micPermission.status()) {
                                    PermissionStatus.GRANTED -> startRecording()
                                    PermissionStatus.DENIED -> {
                                        when (micPermission.request()) {
                                            PermissionStatus.GRANTED -> startRecording()
                                            PermissionStatus.DENIED -> uiState = UiState.PermissionDenied
                                        }
                                    }
                                }
                            }
                        }
                    }) {
                        Text(if (uiState == UiState.Recording) "Stop" else "Record")
                    }
                }
            }

            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                transcript.forEach { segment -> Text(segment) }
            }
        }
    }
}