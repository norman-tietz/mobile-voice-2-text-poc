package ai.healthcarepoc.voice

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationWillResignActiveNotification
import platform.darwin.NSObjectProtocol

@OptIn(ExperimentalForeignApi::class)
actual class AppLifecycleObserver actual constructor(private val onBackground: () -> Unit) {
    private var observers: List<NSObjectProtocol> = emptyList()

    actual fun start() {
        // DidEnterBackground alone only fires once the app is actually backgrounded, missing
        // the class of transient foreground loss Android's onPause() also covers: an incoming
        // call, Siri, or the notification center raise an AVAudioSession interruption and only
        // ever post WillResignActive. Without it, the input tap stops delivering buffers for
        // the interruption's duration while uiState stays Recording, silently losing whatever's
        // said. Ordinary backgrounding posts WillResignActive then DidEnterBackground in
        // sequence; onBackground's own uiState==Recording check (see App.kt) already makes the
        // second call here a no-op, so no extra dedup is needed.
        observers = listOf(UIApplicationWillResignActiveNotification, UIApplicationDidEnterBackgroundNotification)
            .map { name ->
                NSNotificationCenter.defaultCenter.addObserverForName(
                    name = name,
                    `object` = null,
                    queue = NSOperationQueue.mainQueue
                ) { _ -> onBackground() }
            }
    }

    actual fun stop() {
        observers.forEach { NSNotificationCenter.defaultCenter.removeObserver(it) }
        observers = emptyList()
    }
}