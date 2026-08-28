package ai.healthcarepoc.voice

import java.io.File

// VmRSS in /proc/self/status is the same resident-set-size figure `adb shell dumpsys meminfo`
// reports, and unlike PSS (android.app.ActivityManager.getProcessMemoryInfo) it needs no
// Context/ActivityManager - just this process reading its own kernel-exposed status file, which
// every process is always permitted to do.
actual fun currentResidentMemoryMb(): Long? = try {
    File("/proc/self/status")
        .useLines { lines -> lines.firstOrNull { it.startsWith("VmRSS:") } }
        // Line format is "VmRSS:\t   123456 kB" - the numeric field is whitespace-delimited.
        ?.trim()?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull()
        ?.let { kb -> kb / 1024 }
} catch (e: Exception) {
    debugLog("currentResidentMemoryMb: failed to read /proc/self/status: $e")
    null
}
