package ai.healthcarepoc.voice

import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFAudio.AVAudioEngine
import platform.UIKit.UIViewController

// AVAudioSession.sampleRate is not exposed by this Kotlin/Native distribution's AVFAudio
// cinterop bindings (verified via `klib dump-metadata` on the bundled platform klib: the
// AVAudioSession class body has no sampleRate member, even though it exists in Apple's
// AVAudioSession.h). AVAudioEngine's input node format is queried instead, mirroring the
// same outputFormatForBus(0u) call AudioCapture.ios.kt already uses to read the device's
// native hardware sample rate.
//
// Computed once here (not inside the nativeSampleRateHz lambda): App.startRecording()'s audio
// callback calls nativeSampleRateHz() on every captured buffer (~every 100ms), and the device's
// native sample rate does not change mid-session. A short-lived AVAudioEngine is only
// instantiated once, at MainViewController construction time, purely to read this value --
// never per-buffer, and never in addition to the long-lived engine AudioCapture.ios.kt already
// runs for actual capture.
@OptIn(ExperimentalForeignApi::class)
fun MainViewController(): UIViewController = ComposeUIViewController {
    val nativeSampleRateHz = AVAudioEngine().inputNode.outputFormatForBus(0u).sampleRate.toInt()
    App(
        audioCapture = AudioCapture(),
        micPermission = MicPermission(ApplicationContext()),
        modelPathProvider = ModelPathProvider(ApplicationContext()),
        nativeSampleRateHz = { nativeSampleRateHz }
    )
}
