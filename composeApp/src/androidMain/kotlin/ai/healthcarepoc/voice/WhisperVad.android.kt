package ai.healthcarepoc.voice

actual class WhisperVad actual constructor(modelPath: String) : VoiceActivityDetector {
    private val handle: Long = run {
        debugLog("WhisperVad.<init>: calling nativeInit, modelPath=$modelPath")
        val result = nativeInit(modelPath)
        check(result != 0L) { "Failed to load VAD model at $modelPath" }
        result
    }

    actual override fun feed(samples: FloatArray) {
        nativeFeed(handle, samples)
    }

    actual override fun segments(minSilenceDurationMs: Int): List<ClosedFloatingPointRange<Float>> {
        val flat = nativeSegments(handle, minSilenceDurationMs)
        return (flat.indices step 2).map { i -> flat[i]..flat[i + 1] }
    }

    actual override fun resetState() {
        nativeResetState(handle)
    }

    actual fun release() {
        nativeRelease(handle)
    }

    private external fun nativeInit(modelPath: String): Long
    private external fun nativeFeed(handle: Long, samples: FloatArray)
    private external fun nativeSegments(handle: Long, minSilenceDurationMs: Int): FloatArray
    private external fun nativeResetState(handle: Long)
    private external fun nativeRelease(handle: Long)

    companion object {
        init {
            System.loadLibrary("whisper_jni")
        }
    }
}
