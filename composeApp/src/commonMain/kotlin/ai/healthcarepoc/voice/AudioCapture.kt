package ai.healthcarepoc.voice

expect class AudioCapture {
    // The sample rate start() actually captures at. Queried from the platform's own capture
    // engine (fresh on iOS at start() time; a fixed constant on Android) rather than supplied
    // separately by the caller - a value read once elsewhere (e.g. at app launch) can go stale
    // if the audio route changes (a Bluetooth headset connecting) before Record is tapped.
    val sampleRateHz: Int

    fun start(onSamples: (FloatArray) -> Unit)
    fun stop()
}
