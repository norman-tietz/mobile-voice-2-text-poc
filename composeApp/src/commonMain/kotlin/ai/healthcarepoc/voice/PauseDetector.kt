package ai.healthcarepoc.voice

import kotlin.math.sqrt

class PauseDetector(
    private val sampleRateHz: Int,
    private val silenceThresholdRms: Float = 0.02f,
    private val minSilenceDurationMs: Int = 700
) {
    private var trailingSilenceMs: Int = 0

    // Whether any chunk since the last reset() crossed the speech threshold. A pause
    // firing doesn't mean the buffer had speech in it - a segment can be pure silence/
    // background noise from start to finish (trailingSilenceMs still counts up to the
    // pause threshold). Whisper is prone to hallucinating plausible-looking text for
    // silence, so the caller uses this to skip transcribing segments that never had
    // real speech instead of feeding the model dead air.
    var hadSpeech: Boolean = false
        private set

    fun accept(samples: FloatArray): Boolean {
        val rms = rms(samples)
        val chunkDurationMs = (samples.size * 1000) / sampleRateHz

        if (rms < silenceThresholdRms) {
            trailingSilenceMs += chunkDurationMs
        } else {
            trailingSilenceMs = 0
            hadSpeech = true
        }

        val isPause = trailingSilenceMs >= minSilenceDurationMs
        debugLog("PauseDetector.accept: samples=${samples.size} rms=$rms threshold=$silenceThresholdRms trailingSilenceMs=$trailingSilenceMs isPause=$isPause")
        return isPause
    }

    fun reset() {
        debugLog("PauseDetector.reset")
        trailingSilenceMs = 0
        hadSpeech = false
    }

    private fun rms(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var sumSquares = 0.0
        for (s in samples) sumSquares += s.toDouble() * s.toDouble()
        return sqrt(sumSquares / samples.size).toFloat()
    }
}
