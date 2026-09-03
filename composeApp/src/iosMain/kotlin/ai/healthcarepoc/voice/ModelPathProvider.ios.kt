package ai.healthcarepoc.voice

import platform.Foundation.NSBundle

actual class ModelPathProvider actual constructor(context: ApplicationContext) {
    actual fun resolveModelPath(): String = resolveBundleResource("ggml-small")
    actual fun resolveClinicalModelPath(): String = resolveBundleResource("ggml-small-clinical-de")
    actual fun resolveVadModelPath(): String = resolveBundleResource("ggml-silero-v6.2.0")

    private fun resolveBundleResource(name: String): String {
        return NSBundle.mainBundle.pathForResource(name, ofType = "bin")
            ?: error("$name.bin not found in app bundle")
    }
}