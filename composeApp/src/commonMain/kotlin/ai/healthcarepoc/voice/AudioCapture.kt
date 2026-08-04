package ai.healthcarepoc.voice

expect class AudioCapture {
    fun start(onSamples: (FloatArray) -> Unit)
    fun stop()
}
