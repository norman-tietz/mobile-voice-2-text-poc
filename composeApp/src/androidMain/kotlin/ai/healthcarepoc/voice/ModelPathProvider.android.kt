package ai.healthcarepoc.voice

import java.io.File

actual class ModelPathProvider actual constructor(private val context: ApplicationContext) {
    actual fun resolveModelPath(): String {
        val dest = File(context.filesDir, "ggml-small.bin")
        if (!dest.exists()) {
            context.assets.open("models/ggml-small.bin").use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return dest.absolutePath
    }
}