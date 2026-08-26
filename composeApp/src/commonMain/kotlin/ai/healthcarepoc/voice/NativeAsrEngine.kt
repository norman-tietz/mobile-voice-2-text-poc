package ai.healthcarepoc.voice

interface NativeAsrEngine {
    fun isAvailable(): Boolean
    suspend fun start(onSegment: (String) -> Unit, onError: (String) -> Unit)
    suspend fun stop()
}
