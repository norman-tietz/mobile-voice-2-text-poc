package ai.healthcarepoc.voice

import platform.Foundation.NSBundle

actual class ModelPathProvider actual constructor(context: ApplicationContext) {
    actual fun resolveModelPath(): String {
        return NSBundle.mainBundle.pathForResource("ggml-small", ofType = "bin")
            ?: error("ggml-small.bin not found in app bundle")
    }
}