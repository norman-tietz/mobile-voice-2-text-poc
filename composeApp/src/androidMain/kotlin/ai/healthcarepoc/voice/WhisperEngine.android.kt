package ai.healthcarepoc.voice

actual class WhisperEngine actual constructor(modelPath: String) : Transcriber {
    private val handle: Long = run {
        debugLog("WhisperEngine.<init>: calling nativeInit, modelPath=$modelPath")
        val t0 = System.currentTimeMillis()
        val result = nativeInit(modelPath)
        debugLog("WhisperEngine.<init>: nativeInit returned handle=$result after ${System.currentTimeMillis() - t0}ms")
        check(result != 0L) { "Failed to load Whisper model at $modelPath" }
        result
    }

    actual override fun transcribe(samples: FloatArray): String {
        debugLog("WhisperEngine.transcribe: calling nativeTranscribe, samples=${samples.size} (~${samples.size / 16000.0}s @ 16kHz)")
        val t0 = System.currentTimeMillis()
        val result = nativeTranscribe(handle, samples)
        debugLog("WhisperEngine.transcribe: nativeTranscribe returned after ${System.currentTimeMillis() - t0}ms, textLength=${result.length}")
        return result
    }

    actual fun release() {
        nativeRelease(handle)
    }

    override fun resetContext() {
        debugLog("WhisperEngine.resetContext: clearing carried-over prompt tokens")
        nativeResetContext(handle)
    }

    private external fun nativeInit(modelPath: String): Long
    private external fun nativeTranscribe(handle: Long, samples: FloatArray): String
    private external fun nativeResetContext(handle: Long)
    private external fun nativeRelease(handle: Long)

    companion object {
        init {
            System.loadLibrary("whisper_jni")
        }
    }
}