package ai.healthcarepoc.voice

interface Transcriber {
    fun transcribe(samples: FloatArray): String

    // Clears any decoding context carried over between transcribe() calls (e.g. prior
    // segment tokens used as a prompt), so the next recording session starts blind
    // instead of being biased by the previous session's tail.
    fun resetContext()
}

// Just the transcribe stage of the pipeline - segmentation (deciding where a segment
// starts/ends) is SpeechSegmenter's job, running in a separate, real-time coroutine so
// it's never coupled to how long transcribe() takes. See docs/superpowers/specs/
// 2026-08-25-vad-segmentation-design.md.
class TranscriptionSession(private val transcriber: Transcriber) {
    private val finalizedSegments = mutableListOf<String>()

    val segments: List<String> get() = finalizedSegments.toList()

    fun transcribeSegment(samples: FloatArray) {
        debugLog("TranscriptionSession.transcribeSegment: starting transcribe() on ${samples.size} samples")
        val startMs = nowMs()
        val text = transcriber.transcribe(samples)
        val elapsedMs = nowMs() - startMs
        debugLog("TranscriptionSession.transcribeSegment: transcribe() returned after ${elapsedMs}ms, textLength=${text.length}, text=\"$text\"")
        finalizedSegments.add(text)
    }

    fun stop() {
        debugLog("TranscriptionSession.stop: resetting transcriber context")
        transcriber.resetContext()
    }
}
