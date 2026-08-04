package ai.healthcarepoc.voice

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readValue
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import whispercinterop.whisper_context_default_params
import whispercinterop.whisper_full
import whispercinterop.whisper_full_default_params
import whispercinterop.whisper_full_get_segment_text
import whispercinterop.whisper_full_n_segments
import whispercinterop.whisper_free
import whispercinterop.whisper_init_from_file_with_params
import whispercinterop.whisper_sampling_strategy

@OptIn(ExperimentalForeignApi::class)
actual class WhisperEngine actual constructor(modelPath: String) : Transcriber {

    private val ctx = memScoped {
        whisper_init_from_file_with_params(modelPath, whisper_context_default_params())
            ?: error("Failed to load Whisper model at $modelPath")
    }

    actual override fun transcribe(samples: FloatArray): String = memScoped {
        // whisper_full_default_params returns the struct by value (CValue<whisper_full_params>).
        // getPointer(this) materializes it into scope-scratch memory as a CPointer, and .pointed
        // gives a mutable struct view we can write fields on; .readValue() packages the mutated
        // memory back up as a CValue<T> to pass to whisper_full (which also takes the struct by
        // value).
        val params = whisper_full_default_params(whisper_sampling_strategy.WHISPER_SAMPLING_GREEDY)
            .getPointer(this).pointed
        params.language = "de".cstr.ptr
        params.translate = false
        params.print_progress = false
        params.print_realtime = false

        samples.usePinned { pinned ->
            whisper_full(ctx, params.readValue(), pinned.addressOf(0), samples.size)
        }

        val segmentCount = whisper_full_n_segments(ctx)
        buildString {
            for (i in 0 until segmentCount) {
                append(whisper_full_get_segment_text(ctx, i)?.toKString())
            }
        }
    }

    actual fun release() {
        whisper_free(ctx)
    }
}