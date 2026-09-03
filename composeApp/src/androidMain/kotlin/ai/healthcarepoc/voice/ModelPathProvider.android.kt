package ai.healthcarepoc.voice

import java.io.File

actual class ModelPathProvider actual constructor(private val context: ApplicationContext) {
    actual fun resolveModelPath(): String = resolveAsset("ggml-small.bin")
    actual fun resolveClinicalModelPath(): String = resolveAsset("ggml-small-clinical-de.bin")
    actual fun resolveVadModelPath(): String = resolveAsset("ggml-silero-v6.2.0.bin")

    private fun resolveAsset(filename: String): String {
        val dest = File(context.filesDir, filename)
        if (!dest.exists()) {
            // Copy to a temp file first, then rename into place. Copying directly to `dest`
            // meant a process kill mid-copy (backgrounding, low-memory kill, a full disk) left
            // a truncated file that `dest.exists()` alone can't tell apart from a complete one
            // - every later launch would then load that truncated file as if it were valid and
            // fail permanently, with clearing app data as the only recovery. A rename within the
            // same directory (same filesystem) is atomic: dest only ever ends up fully copied or
            // not created at all.
            val tmp = File(context.filesDir, "$filename.tmp")
            context.assets.open("models/$filename").use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            check(tmp.renameTo(dest)) { "Failed to move $tmp to $dest" }
        }
        return dest.absolutePath
    }
}