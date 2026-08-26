package ai.healthcarepoc.voice

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.readValue
import kotlinx.cinterop.usePinned
import platform.Foundation.NSProcessInfo
import whispercinterop.whisper_vad_context_params
import whispercinterop.whisper_vad_default_context_params
import whispercinterop.whisper_vad_default_params
import whispercinterop.whisper_vad_detect_speech_no_reset
import whispercinterop.whisper_vad_free
import whispercinterop.whisper_vad_free_segments
import whispercinterop.whisper_vad_init_from_file_with_params
import whispercinterop.whisper_vad_reset_state
import whispercinterop.whisper_vad_segments_from_probs
import whispercinterop.whisper_vad_segments_get_segment_t0
import whispercinterop.whisper_vad_segments_get_segment_t1
import whispercinterop.whisper_vad_segments_n_segments
import kotlin.math.max

@OptIn(ExperimentalForeignApi::class)
actual class WhisperVad actual constructor(modelPath: String) : VoiceActivityDetector {

    private val vctx = memScoped {
        val cparams = whisper_vad_default_context_params().getPointer(this).pointed
        cparams.n_threads = max(1, NSProcessInfo.processInfo.activeProcessorCount.toInt())
        whisper_vad_init_from_file_with_params(modelPath, cparams.readValue())
            ?: error("Failed to load VAD model at $modelPath")
    }

    actual override fun feed(samples: FloatArray) {
        samples.usePinned { pinned ->
            whisper_vad_detect_speech_no_reset(vctx, pinned.addressOf(0), samples.size)
        }
    }

    actual override fun segments(minSilenceDurationMs: Int): List<ClosedFloatingPointRange<Float>> = memScoped {
        val params = whisper_vad_default_params().getPointer(this).pointed
        params.min_silence_duration_ms = minSilenceDurationMs

        val segments = whisper_vad_segments_from_probs(vctx, params.readValue())
            ?: error("whisper_vad_segments_from_probs returned null")
        val n = whisper_vad_segments_n_segments(segments)
        val result = (0 until n).map { i ->
            whisper_vad_segments_get_segment_t0(segments, i)..whisper_vad_segments_get_segment_t1(segments, i)
        }
        whisper_vad_free_segments(segments)
        result
    }

    actual override fun resetState() {
        whisper_vad_reset_state(vctx)
    }

    actual fun release() {
        whisper_vad_free(vctx)
    }
}
