package ai.healthcarepoc.voice

expect class AppLifecycleObserver(onBackground: () -> Unit) {
    fun start()
    fun stop()
}