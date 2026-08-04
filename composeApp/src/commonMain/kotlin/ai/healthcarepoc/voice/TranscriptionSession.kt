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
        if (pauseDetector.accept(samples)) {
            finalizeSegment()
        }
    }

    fun stop(): List<String> {
        if (pendingSamples.isNotEmpty()) {
            finalizeSegment()
        }
        return segments
    }

    private fun finalizeSegment() {
        val text = transcriber.transcribe(pendingSamples.toFloatArray())
        finalizedSegments.add(text)
        pendingSamples.clear()
        pauseDetector.reset()
    }
}