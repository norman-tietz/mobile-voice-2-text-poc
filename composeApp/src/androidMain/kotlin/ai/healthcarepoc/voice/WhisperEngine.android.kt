package ai.healthcarepoc.voice

actual class WhisperEngine actual constructor(modelPath: String) : Transcriber {
    private val handle: Long = nativeInit(modelPath).also {
        check(it != 0L) { "Failed to load Whisper model at $modelPath" }
    }

    actual override fun transcribe(samples: FloatArray): String {
        return nativeTranscribe(handle, samples)
    }

    actual fun release() {
        nativeRelease(handle)
    }

    private external fun nativeInit(modelPath: String): Long
    private external fun nativeTranscribe(handle: Long, samples: FloatArray): String
    private external fun nativeRelease(handle: Long)

    companion object {
        init {
            System.loadLibrary("whisper_jni")
        }
    }
}