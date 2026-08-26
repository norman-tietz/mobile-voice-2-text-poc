package ai.healthcarepoc.voice

interface Transcriber {
    fun transcribe(samples: FloatArray): String

    // Clears any decoding context carried over between transcribe() calls (e.g. prior
    // segment tokens used as a prompt), so the next recording session starts blind
    // instead of being biased by the previous session's tail.
    fun resetContext()
}

// Per-segment timing, returned from transcribeSegment() so callers (see App.kt's on-screen
// metrics summary) don't have to re-derive what's already logged below.
data class SegmentMetrics(val elapsedMs: Long, val audioDurationMs: Long, val rtf: Double)

// Just the transcribe stage of the pipeline - segmentation (deciding where a segment
// starts/ends) is SpeechSegmenter's job, running in a separate, real-time coroutine so
// it's never coupled to how long transcribe() takes. See docs/superpowers/specs/
// 2026-08-25-vad-segmentation-design.md.
class TranscriptionSession(
    private val transcriber: Transcriber,
    private val sampleRateHz: Int = 16_000
) {
    private val finalizedSegments = mutableListOf<String>()

    val segments: List<String> get() = finalizedSegments.toList()

    fun transcribeSegment(samples: FloatArray): SegmentMetrics {
        debugLog("TranscriptionSession.transcribeSegment: starting transcribe() on ${samples.size} samples")
        val startMs = nowMs()
        val text = transcriber.transcribe(samples)
        val elapsedMs = nowMs() - startMs
        // Real-time factor (decode time / audio duration) normalizes transcribe() cost across
        // segments of different lengths, which is what makes it comparable across devices.
        val audioDurationMs = (samples.size * 1000L) / sampleRateHz
        val rtf = if (audioDurationMs > 0) roundTo2(elapsedMs.toDouble() / audioDurationMs) else 0.0
        debugLog(
            "TranscriptionSession.transcribeSegment: transcribe() returned after ${elapsedMs}ms " +
                "for ${audioDurationMs}ms of audio (RTF=$rtf), textLength=${text.length}, text=\"$text\""
        )
        finalizedSegments.add(text)
        return SegmentMetrics(elapsedMs, audioDurationMs, rtf)
    }

    fun stop() {
        debugLog("TranscriptionSession.stop: resetting transcriber context")
        transcriber.resetContext()
    }
}

internal fun roundTo2(value: Double): Double = kotlin.math.round(value * 100) / 100.0
