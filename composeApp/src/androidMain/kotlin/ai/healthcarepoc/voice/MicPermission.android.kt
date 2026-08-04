package ai.healthcarepoc.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine

actual class MicPermission actual constructor(private val context: ApplicationContext) {

    actual fun status(): PermissionStatus {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val result = if (granted) PermissionStatus.GRANTED else PermissionStatus.DENIED
        debugLog("MicPermission.status: $result")
        return result
    }

    actual suspend fun request(): PermissionStatus = suspendCancellableCoroutine { continuation ->
        debugLog("MicPermission.request: entered, requesting RECORD_AUDIO")
        PermissionRequestBridge.pendingContinuation = continuation
        context.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), PermissionRequestBridge.REQUEST_CODE)
    }

    actual fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
        context.startActivity(intent)
    }
}

object PermissionRequestBridge {
    const val REQUEST_CODE = 4321
    var pendingContinuation: kotlinx.coroutines.CancellableContinuation<PermissionStatus>? = null

    fun onRequestPermissionsResult(requestCode: Int, grantResults: IntArray) {
        debugLog("PermissionRequestBridge.onRequestPermissionsResult: requestCode=$requestCode grantResults=${grantResults.toList()} pendingContinuation=${if (pendingContinuation == null) "null" else "present"}")
        if (requestCode != REQUEST_CODE) return
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        pendingContinuation?.resumeWith(
            Result.success(if (granted) PermissionStatus.GRANTED else PermissionStatus.DENIED)
        )
        pendingContinuation = null
    }
}
