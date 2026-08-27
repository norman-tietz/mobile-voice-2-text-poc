package ai.healthcarepoc.voice

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readValue
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.Foundation.NSProcessInfo
import whispercinterop.whisper_context_default_params
import whispercinterop.whisper_full
import whispercinterop.whisper_full_default_params
import whispercinterop.whisper_full_get_segment_text
import whispercinterop.whisper_full_get_token_id
import whispercinterop.whisper_full_n_segments
import whispercinterop.whisper_full_n_tokens
import whispercinterop.whisper_free
import whispercinterop.whisper_init_from_file_with_params
import whispercinterop.whisper_n_audio_ctx
import whispercinterop.whisper_sampling_strategy
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

@OptIn(ExperimentalForeignApi::class)
actual class WhisperEngine actual constructor(modelPath: String) : Transcriber {

    private val ctx = memScoped {
        whisper_init_from_file_with_params(modelPath, whisper_context_default_params())
            ?: error("Failed to load Whisper model at $modelPath")
    }

    // Previous segment's decoded tokens, fed back in as wparams.prompt_tokens on the
    // next transcribe() call (whisper.cpp's "condition on previous text" pattern) so a
    // pause-triggered chunk isn't decoded in isolation from what was just said.
    private var prevTokens: List<Int> = emptyList()

    actual override fun transcribe(samples: FloatArray): String = memScoped {
        // whisper_full_default_params returns the struct by value (CValue<whisper_full_params>).
        // getPointer(this) materializes it into scope-scratch memory as a CPointer, and .pointed
        // gives a mutable struct view we can write fields on; .readValue() packages the mutated
        // memory back up as a CValue<T> to pass to whisper_full (which also takes the struct by
        // value).
        // Beam search (whisper.cpp sets beam_size=5 automatically for this strategy) is
        // markedly less prone than greedy decoding to getting stuck repeating a phrase -
        // confirmed on-device: greedy decoding produced verbatim tripled/doubled phrases
        // on some real-speech segments (e.g. "mit normalen Pausen, mit normalen Pausen,
        // mit normalen Pausen."), one of which also ballooned to 45s of decode time since
        // max_tokens was unbounded. max_tokens below is a hard backstop on top of that.
        val params = whisper_full_default_params(whisper_sampling_strategy.WHISPER_SAMPLING_BEAM_SEARCH)
            .getPointer(this).pointed
        // Widened from whisper.cpp's own default of 5: more candidate hypotheses per segment
        // helps recover the correct word when the acoustic signal is ambiguous (unclear/quiet
        // speech), at the cost of decode time scaling roughly with beam size.
        params.beam_search.beam_size = 8
        params.language = "de".cstr.ptr
        params.translate = false
        params.print_progress = false
        params.print_realtime = false
        params.n_threads = max(1, NSProcessInfo.processInfo.activeProcessorCount.toInt())
        params.max_tokens = 224
        if (prevTokens.isNotEmpty()) {
            val promptArray = allocArray<IntVar>(prevTokens.size)
            prevTokens.forEachIndexed { index, token -> promptArray[index] = token }
            params.prompt_tokens = promptArray
            params.prompt_n_tokens = prevTokens.size
        }

        // Left at its default (0), audio_ctx makes whisper.cpp always encode the
        // model's full 30s/1500-frame context no matter how short the input is, so
        // every call costs the same regardless of segment length. audio_ctx is in
        // encoder frames (WHISPER_CHUNK_SIZE seconds -> max_ctx frames), so size it
        // to the actual audio length - but generously: too tight a window starves the
        // decoder of acoustic grounding near the end of the segment, which can send it
        // into a token-repetition loop (max_tokens is unbounded by default, so a stuck
        // decode both repeats/garbles the transcript and runs far longer than normal).
        // 256 frames (~5.12s) minimum plus a 3s margin keeps most of the speedup for
        // typical short segments while leaving real headroom.
        val maxCtx = whisper_n_audio_ctx(ctx)
        val seconds = samples.size / 16000f
        val framesPerSec = maxCtx / 30f
        val minCtx = 256
        val wantedCtx = max(minCtx, ceil(seconds * framesPerSec).toInt() + (3 * framesPerSec).toInt())
        params.audio_ctx = min(maxCtx, wantedCtx)

        val fullResult = samples.usePinned { pinned ->
            whisper_full(ctx, params.readValue(), pinned.addressOf(0), samples.size)
        }
        // Previously ignored: a decode failure leaves the context with zero segments, which the
        // code below read as "zero segments" and returned as a successful empty transcription -
        // silently dropping speech and, worse, wiping prevTokens below on a failure that had
        // nothing to do with silence. Throwing here surfaces a real error (transcribe()'s caller
        // is consumerJob, a coroutine covered by App.kt's CoroutineExceptionHandler) and leaves
        // prevTokens untouched, so the next segment isn't decoded blind either.
        check(fullResult == 0) { "whisper_full failed with code $fullResult" }

        val segmentCount = whisper_full_n_segments(ctx)
        val text = buildString {
            for (i in 0 until segmentCount) {
                append(whisper_full_get_segment_text(ctx, i)?.toKString())
            }
        }

        prevTokens = buildList {
            for (i in 0 until segmentCount) {
                val tokenCount = whisper_full_n_tokens(ctx, i)
                for (j in 0 until tokenCount) {
                    add(whisper_full_get_token_id(ctx, i, j))
                }
            }
        }

        text
    }

    override fun resetContext() {
        debugLog("WhisperEngine.resetContext: clearing carried-over prompt tokens")
        prevTokens = emptyList()
    }

    actual fun release() {
        whisper_free(ctx)
    }
}