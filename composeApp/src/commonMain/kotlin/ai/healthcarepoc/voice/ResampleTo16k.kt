package ai.healthcarepoc.voice

fun resampleTo16k(samples: FloatArray, sourceSampleRateHz: Int): FloatArray {
    if (sourceSampleRateHz == 16_000) return samples
    if (sourceSampleRateHz <= 0) {
        // Runs on the raw audio-capture callback (iOS: AVAudioEngine's realtime tap thread;
        // Android: the reader thread) - not a coroutine, so throwing here wouldn't be caught by
        // App.kt's CoroutineExceptionHandler and would crash the process instead. A non-positive
        // rate (seen on iOS when queried before a record-capable AVAudioSession is configured)
        // would otherwise make ratio = +Infinity and outputSize saturate to Int.MAX_VALUE,
        // attempting an ~8GB allocation. Drop the buffer instead - one dropped chunk is far
        // preferable to a crash.
        debugLog("resampleTo16k: dropping buffer, invalid sourceSampleRateHz=$sourceSampleRateHz")
        return FloatArray(0)
    }
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