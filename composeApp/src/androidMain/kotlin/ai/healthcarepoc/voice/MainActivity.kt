package ai.healthcarepoc.voice

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            App(
                audioCapture = AudioCapture(),
                micPermission = MicPermission(this),
                modelPathProvider = ModelPathProvider(this),
                nativeSampleRateHz = { 16_000 }
            )
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        PermissionRequestBridge.onRequestPermissionsResult(requestCode, grantResults)
    }

    override fun onPause() {
        super.onPause()
        AppLifecycleBridge.onBackgroundCallback?.invoke()
    }
}
