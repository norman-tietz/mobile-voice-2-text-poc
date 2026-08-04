package ai.healthcarepoc.voice

expect class WhisperEngine(modelPath: String) : Transcriber {
    override fun transcribe(samples: FloatArray): String
    fun release()
}