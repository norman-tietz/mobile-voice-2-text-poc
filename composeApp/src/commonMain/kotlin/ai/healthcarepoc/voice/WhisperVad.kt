package ai.healthcarepoc.voice

expect class WhisperVad(modelPath: String) : VoiceActivityDetector {
    override fun speechProbability(samples: FloatArray): Float
    override fun resetState()
    fun release()
}
