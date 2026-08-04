package ai.healthcarepoc.voice

fun resampleTo16k(samples: FloatArray, sourceSampleRateHz: Int): FloatArray {
    if (sourceSampleRateHz == 16_000) return samples
    val ratio = 16_000.0 / sourceSampleRateHz
    val outputSize = (samples.size * ratio).toInt()
    return FloatArray(outputSize) { i ->
        val sourceIndex = (i / ratio)
        val lower = sourceIndex.toInt().coerceIn(0, samples.size - 1)
        val upper = (lower + 1).coerceIn(0, samples.size - 1)
        val frac = (sourceIndex - lower).toFloat()
        samples[lower] * (1 - frac) + samples[upper] * frac
    }
}