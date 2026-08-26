package ai.healthcarepoc.voice

import androidx.compose.runtime.Composable

// Prevents the device from sleeping while `enabled` is true - used to keep the screen (and
// mic capture) alive for the duration of a recording, since a sleeping device stops the app
// (see AppLifecycleObserver's onBackground handling).
@Composable
expect fun KeepScreenOn(enabled: Boolean)
