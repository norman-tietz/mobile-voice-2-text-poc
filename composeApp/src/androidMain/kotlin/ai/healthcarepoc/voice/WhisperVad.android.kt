package ai.healthcarepoc.voice

actual class WhisperVad actual constructor(modelPath: String) : VoiceActivityDetector {
    private val handle: Long = run {
        debugLog("WhisperVad.<init>: calling nativeInit, modelPath=$modelPath")
        val result = nativeInit(modelPath)
        check(result != 0L) { "Failed to load VAD model at $modelPath" }
        result
    }

    actual override fun speechProbability(samples: FloatArray): Float {
        return nativeSpeechProbability(handle, samples)
    }

    actual override fun resetState() {
        nativeResetState(handle)
    }

    actual fun release() {
        nativeRelease(handle)
    }

    private external fun nativeInit(modelPath: String): Long
    private external fun nativeSpeechProbability(handle: Long, samples: FloatArray): Float
    private external fun nativeResetState(handle: Long)
    private external fun nativeRelease(handle: Long)

    companion object {
        init {
            System.loadLibrary("whisper_jni")
        }
    }
}
