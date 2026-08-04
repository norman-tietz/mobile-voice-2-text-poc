package ai.healthcarepoc.voice

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionRecordPermissionGranted
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString

// Uses the pre-iOS-17 AVAudioSession permission API rather than AVAudioApplication (iOS 17+
// only) because the project's deployment target is iOS 15.0 (see iosApp.xcodeproj). AVAudioSession's
// requestRecordPermission/recordPermission are available since iOS 8 and remain fully functional
// (soft-deprecated, not removed) on iOS 17+, so no runtime OS-version branch is needed.
@OptIn(ExperimentalForeignApi::class)
actual class MicPermission actual constructor(private val context: ApplicationContext) {

    actual fun status(): PermissionStatus {
        val granted = AVAudioSession.sharedInstance().recordPermission == AVAudioSessionRecordPermissionGranted
        return if (granted) PermissionStatus.GRANTED else PermissionStatus.DENIED
    }

    actual suspend fun request(): PermissionStatus = suspendCancellableCoroutine { continuation ->
        AVAudioSession.sharedInstance().requestRecordPermission { granted ->
            continuation.resumeWith(
                Result.success(if (granted) PermissionStatus.GRANTED else PermissionStatus.DENIED)
            )
        }
    }

    actual fun openAppSettings() {
        val url = NSURL(string = UIApplicationOpenSettingsURLString)
        UIApplication.sharedApplication.openURL(url)
    }
}
