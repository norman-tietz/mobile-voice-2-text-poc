package ai.healthcarepoc.voice

import kotlin.time.TimeSource

// Temporary diagnostic logging to trace the recording/transcription pipeline while debugging
// the "Stop doesn't stop, no transcript" issue. Only ever logs counts/timings/booleans, never
// transcript text or raw audio content. Android-only in effect: println() is redirected to
// Logcat (tag "System.out") on Android, and is a harmless no-op-ish stdout write elsewhere.
// Remove once the root cause is confirmed and fixed.
fun debugLog(message: String) {
    println("[VoiceDebug] $message")
}

private val debugTimeOrigin = TimeSource.Monotonic.markNow()

// Monotonic milliseconds since app start, for measuring elapsed time in debug logs
// (kotlin.system.currentTimeMillis isn't available in commonMain).
fun nowMs(): Long = debugTimeOrigin.elapsedNow().inWholeMilliseconds
