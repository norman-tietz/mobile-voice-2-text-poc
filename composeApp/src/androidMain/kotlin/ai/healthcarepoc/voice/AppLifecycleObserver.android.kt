package ai.healthcarepoc.voice

actual class AppLifecycleObserver actual constructor(private val onBackground: () -> Unit) {
    actual fun start() {
        AppLifecycleBridge.onBackgroundCallback = onBackground
    }

    actual fun stop() {
        AppLifecycleBridge.onBackgroundCallback = null
    }
}

object AppLifecycleBridge {
    var onBackgroundCallback: (() -> Unit)? = null
}