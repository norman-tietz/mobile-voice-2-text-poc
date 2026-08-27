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
import kotlinx.coroutines.withTimeoutOrNull

class AndroidSpeechRecognizerEngine(private val context: Context) : NativeAsrEngine {

    private var recognizer: SpeechRecognizer? = null
    private var active = false
    private var segmentCallback: ((String) -> Unit)? = null
    private var errorCallback: ((String) -> Unit)? = null
    private var stopSignal: CompletableDeferred<Unit>? = null
    // Incremented on every start(). Each RecognitionListener instance closes over the
    // generation it was created for and ignores its own callbacks once this no longer matches -
    // a callback the just-destroyed recognizer already posted to the main looper before stop()'s
    // teardown ran would otherwise still fire, read the NEW generation's active/stopSignal state,
    // and misapply itself (e.g. calling startListening() on the new recognizer while it's already
    // listening, or appending stale text to the new recording's transcript).
    private var generation = 0

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
            val myGeneration = ++generation
            this@AndroidSpeechRecognizerEngine.segmentCallback = onSegment
            this@AndroidSpeechRecognizerEngine.errorCallback = onError
            active = true
            try {
                val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                recognizer = r
                r.setRecognitionListener(createListener(myGeneration))
                debugLog("AndroidSpeechRecognizerEngine.start: recognizer created, starting listening")
                r.startListening(buildIntent())
            } catch (e: Throwable) {
                // Roll back to the same clean state stop()'s !active branch expects - otherwise
                // active stays true with recognizer null forever, and a later stop() call would
                // take the active branch, call stopListening() on null, and block for the full
                // 5-second timeout waiting for a callback that can never arrive.
                active = false
                recognizer = null
                throw e
            }
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
        val completed = withTimeoutOrNull(5_000) {
            deferred.await()
        }
        // stopSignal must be cleared in the same Main continuation that tears down the
        // callbacks below - splitting these into separate withContext blocks yields the Main
        // dispatcher in between, leaving a window where a late RecognitionListener callback
        // sees stopSignal == null and active == false but segmentCallback/errorCallback still
        // set, and invokes them after stop() has already returned.
        withContext(Dispatchers.Main) {
            if (completed == null) {
                debugLog("AndroidSpeechRecognizerEngine.stop: timeout waiting for recognizer callback")
                stopSignal = null
            }
            recognizer?.destroy()
            recognizer = null
            segmentCallback = null
            errorCallback = null
        }
        debugLog("AndroidSpeechRecognizerEngine.stop: recognizer destroyed")
    }

    private fun buildIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "de-DE")
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }

    // A fresh listener per start() call (not a single shared instance) so each one can close
    // over the generation it belongs to and ignore its own callbacks once stale - see the
    // `generation` field comment above.
    private fun createListener(myGeneration: Int): RecognitionListener = object : RecognitionListener {
        private fun isStale() = myGeneration != generation

        override fun onResults(results: Bundle) {
            if (isStale()) {
                debugLog("AndroidSpeechRecognizerEngine.onResults: stale generation $myGeneration (current $generation), ignoring")
                return
            }
            val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            debugLog("AndroidSpeechRecognizerEngine.onResults: active=$active, textLength=${text.length}")
            if (text.isNotEmpty()) {
                segmentCallback?.invoke(text)
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
            if (isStale()) {
                debugLog("AndroidSpeechRecognizerEngine.onError: stale generation $myGeneration (current $generation), ignoring")
                return
            }
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
                    val errorMsg = errorMessage(error)
                    recognizer?.destroy()
                    recognizer = null
                    segmentCallback = null
                    val onErrorCallback = errorCallback
                    errorCallback = null
                    onErrorCallback?.invoke(errorMsg)
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
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "German on-device recognition is not supported on this device"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "German on-device recognition is not installed — add it in Settings > System > Languages > On-device speech recognition"
        SpeechRecognizer.ERROR_NETWORK -> "Network error"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
        SpeechRecognizer.ERROR_SERVER -> "Server error"
        else -> "Speech recognizer error ($error)"
    }
}
