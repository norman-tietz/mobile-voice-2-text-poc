package ai.healthcarepoc.voice

interface Transcriber {
    fun transcribe(samples: FloatArray): String
}

class TranscriptionSession(
    private val transcriber: Transcriber,
    private val pauseDetector: PauseDetector
) {
    private val finalizedSegments = mutableListOf<String>()
    private val pendingSamples = mutableListOf<Float>()

    val segments: List<String> get() = finalizedSegments.toList()

    fun acceptAudio(samples: FloatArray) {
        pendingSamples.addAll(samples.toList())
        debugLog("TranscriptionSession.acceptAudio: +${samples.size} samples, pending=${pendingSamples.size}")
        if (pauseDetector.accept(samples)) {
            debugLog("TranscriptionSession.acceptAudio: pause detected, finalizing segment")
            finalizeSegment()
        }
    }

    fun stop(): List<String> {
        debugLog("TranscriptionSession.stop: entered, pending=${pendingSamples.size}")
        if (pendingSamples.isNotEmpty()) {
            finalizeSegment()
        }
        debugLog("TranscriptionSession.stop: returning ${segments.size} segments")
        return segments
    }

    private fun finalizeSegment() {
        if (!pauseDetector.hadSpeech) {
            debugLog("TranscriptionSession.finalizeSegment: skipping transcribe(), ${pendingSamples.size} samples never crossed the speech threshold")
            pendingSamples.clear()
            pauseDetector.reset()
            return
        }
        debugLog("TranscriptionSession.finalizeSegment: starting transcribe() on ${pendingSamples.size} samples")
        val startMs = nowMs()
        val text = transcriber.transcribe(pendingSamples.toFloatArray())
        val elapsedMs = nowMs() - startMs
        debugLog("TranscriptionSession.finalizeSegment: transcribe() returned after ${elapsedMs}ms, textLength=${text.length}, text=\"$text\"")
        finalizedSegments.add(text)
        pendingSamples.clear()
        pauseDetector.reset()
    }
}