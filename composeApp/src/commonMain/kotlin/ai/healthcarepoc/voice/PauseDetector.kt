package ai.healthcarepoc.voice

import kotlin.math.sqrt

class PauseDetector(
    private val sampleRateHz: Int,
    private val silenceThresholdRms: Float = 0.02f,
    private val minSilenceDurationMs: Int = 700
) {
    private var trailingSilenceMs: Int = 0

    fun accept(samples: FloatArray): Boolean {
        val rms = rms(samples)
        val chunkDurationMs = (samples.size * 1000) / sampleRateHz

        if (rms < silenceThresholdRms) {
            trailingSilenceMs += chunkDurationMs
        } else {
            trailingSilenceMs = 0
        }

        return trailingSilenceMs >= minSilenceDurationMs
    }

    fun reset() {
        trailingSilenceMs = 0
    }

    private fun rms(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var sumSquares = 0.0
        for (s in samples) sumSquares += s.toDouble() * s.toDouble()
        return sqrt(sumSquares / samples.size).toFloat()
    }
}
