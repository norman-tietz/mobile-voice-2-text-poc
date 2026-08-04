package ai.healthcarepoc.voice

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.darwin.NSObjectProtocol

@OptIn(ExperimentalForeignApi::class)
actual class AppLifecycleObserver actual constructor(private val onBackground: () -> Unit) {
    private var observer: NSObjectProtocol? = null

    actual fun start() {
        observer = NSNotificationCenter.defaultCenter.addObserverForName(
            name = UIApplicationDidEnterBackgroundNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue
        ) { _ -> onBackground() }
    }

    actual fun stop() {
        observer?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
        observer = null
    }
}