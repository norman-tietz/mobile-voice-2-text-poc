package ai.healthcarepoc.voice

expect class WhisperVad(modelPath: String) : VoiceActivityDetector {
    override fun feed(samples: FloatArray)
    override fun segments(minSilenceDurationMs: Int): List<ClosedFloatingPointRange<Float>>
    override fun resetState()
    fun release()
}
