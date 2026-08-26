package ai.healthcarepoc.voice

class SpeechSegmenter(
    private val vad: VoiceActivityDetector,
    private val sampleRateHz: Int,
    private val minSilenceDurationMs: Int = 500
) {
    private val buffer = mutableListOf<Float>()
    private var closedSegmentsEmitted = 0
    private var lastEmittedEndSample = 0

    // Feed one capture chunk. Returns any segment(s) that just closed (a segment only
    // closes once minSilenceDurationMs of trailing non-speech follows it).
    fun accept(samples: FloatArray): List<FloatArray> {
        buffer.addAll(samples.toList())
        vad.feed(samples)

        val closed = vad.segments(minSilenceDurationMs)
        val newlyClosed = closed.drop(closedSegmentsEmitted)
        closedSegmentsEmitted = closed.size

        return newlyClosed.map { range ->
            val startSample = (range.start * sampleRateHz).toInt().coerceIn(0, buffer.size)
            val endSample = (range.endInclusive * sampleRateHz).toInt().coerceIn(startSample, buffer.size)
            lastEmittedEndSample = endSample
            buffer.subList(startSample, endSample).toFloatArray()
        }
    }

    // Force-finalizes whatever's still open (end of recording, no pause reached yet).
    // Bounded worst case if this is pure trailing silence rather than real speech: at
    // most minSilenceDurationMs of audio, once per recording - anything longer would
    // already have closed as its own segment via a prior accept() call.
    fun flush(): FloatArray? {
        if (lastEmittedEndSample >= buffer.size) return null
        return buffer.subList(lastEmittedEndSample, buffer.size).toFloatArray()
    }

    // Clears state between recordings.
    fun reset() {
        buffer.clear()
        closedSegmentsEmitted = 0
        lastEmittedEndSample = 0
        vad.resetState()
    }
}
