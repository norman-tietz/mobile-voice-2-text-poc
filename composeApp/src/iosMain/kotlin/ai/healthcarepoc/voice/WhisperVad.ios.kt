package ai.healthcarepoc.voice

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.readValue
import kotlinx.cinterop.usePinned
import platform.Foundation.NSProcessInfo
import whispercinterop.whisper_vad_context_params
import whispercinterop.whisper_vad_default_context_params
import whispercinterop.whisper_vad_detect_speech_no_reset
import whispercinterop.whisper_vad_free
import whispercinterop.whisper_vad_init_from_file_with_params
import whispercinterop.whisper_vad_n_probs
import whispercinterop.whisper_vad_probs
import whispercinterop.whisper_vad_reset_state
import kotlin.math.max

@OptIn(ExperimentalForeignApi::class)
actual class WhisperVad actual constructor(modelPath: String) : VoiceActivityDetector {

    private val vctx = memScoped {
        val cparams = whisper_vad_default_context_params().getPointer(this).pointed
        cparams.n_threads = max(1, NSProcessInfo.processInfo.activeProcessorCount.toInt())
        whisper_vad_init_from_file_with_params(modelPath, cparams.readValue())
            ?: error("Failed to load VAD model at $modelPath")
    }

    // Classifies exactly the given chunk as speech/non-speech. whisper_vad_detect_speech_no_reset
    // overwrites the VAD context's probability buffer with only this call's chunk each time - it
    // does NOT accumulate across calls. Only the model's recurrent hidden state carries forward.
    // So the probabilities must be read back immediately, before the next call overwrites them.
    actual override fun speechProbability(samples: FloatArray): Float {
        // pinned.addressOf(0) below indexes element 0, which throws on an empty array - a zero-
        // frame buffer is a real, reachable input (an AVAudioPCMBuffer route change/engine
        // restart, or a resampleTo16k output that degenerates to size 0), not just a theoretical
        // one. Mirrors the Android JNI bridge, which already tolerates n==0 the same way
        // (GetArrayLength 0 -> n_chunks 0 -> returns 0.0f) without a crash.
        if (samples.isEmpty()) return 0.0f
        val detected = samples.usePinned { pinned ->
            whisper_vad_detect_speech_no_reset(vctx, pinned.addressOf(0), samples.size)
        }
        if (!detected) return 0.0f

        val nProbs = whisper_vad_n_probs(vctx)
        val probs = whisper_vad_probs(vctx) ?: return 0.0f
        var maxProb = 0.0f
        for (i in 0 until nProbs) {
            maxProb = max(maxProb, probs[i])
        }
        return maxProb
    }

    actual override fun resetState() {
        whisper_vad_reset_state(vctx)
    }

    actual fun release() {
        whisper_vad_free(vctx)
    }
}
