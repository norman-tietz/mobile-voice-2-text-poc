package ai.healthcarepoc.voice

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController = ComposeUIViewController {
    App(
        audioCapture = AudioCapture(),
        micPermission = MicPermission(ApplicationContext()),
        modelPathProvider = ModelPathProvider(ApplicationContext())
    )
}
