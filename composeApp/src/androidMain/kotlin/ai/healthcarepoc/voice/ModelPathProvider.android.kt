package ai.healthcarepoc.voice

import java.io.File

actual class ModelPathProvider actual constructor(private val context: ApplicationContext) {
    actual fun resolveModelPath(): String = resolveAsset("ggml-small.bin")
    actual fun resolveVadModelPath(): String = resolveAsset("ggml-silero-v6.2.0.bin")

    private fun resolveAsset(filename: String): String {
        val dest = File(context.filesDir, filename)
        if (!dest.exists()) {
            context.assets.open("models/$filename").use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return dest.absolutePath
    }
}