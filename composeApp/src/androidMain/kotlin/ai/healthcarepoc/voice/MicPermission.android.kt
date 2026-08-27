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
        // A second concurrent call would otherwise silently overwrite pendingContinuation,
        // leaking the first caller's coroutine forever (onRequestPermissionsResult only ever
        // resumes whichever continuation is currently referenced). Failing loudly surfaces a
        // real bug instead of an invisible permanent hang.
        check(PermissionRequestBridge.pendingContinuation == null) {
            "MicPermission.request() called while another request is already pending"
        }
        PermissionRequestBridge.pendingContinuation = continuation
        // If this coroutine is cancelled while the system dialog is still up (e.g. the
        // composition is disposed by a config change), clear the reference - otherwise it keeps
        // pointing at a continuation that can never be resumed, permanently blocking any future
        // request() via the check above until the dialog happens to resolve and overwrite it.
        continuation.invokeOnCancellation {
            if (PermissionRequestBridge.pendingContinuation === continuation) {
                PermissionRequestBridge.pendingContinuation = null
            }
        }
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
