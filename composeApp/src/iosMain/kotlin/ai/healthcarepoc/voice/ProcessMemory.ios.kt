package ai.healthcarepoc.voice

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
import platform.darwin.KERN_SUCCESS
import platform.darwin.MACH_TASK_BASIC_INFO
import platform.darwin.mach_task_basic_info
import platform.darwin.mach_task_self_
import platform.darwin.task_info

// mach_task_basic_info.resident_size is this process's RSS in bytes - the same figure Xcode's
// Memory gauge/Instruments shows. There's no Foundation-level API for a process's own memory
// footprint (NSProcessInfo.physicalMemory is the *device's* total RAM, not this process's), so
// this goes straight to the Mach kernel call every other memory-reporting library on Apple
// platforms is built on.
@OptIn(ExperimentalForeignApi::class)
actual fun currentResidentMemoryMb(): Long? = memScoped {
    val info = alloc<mach_task_basic_info>()
    val count = alloc<UIntVar>()
    count.value = (sizeOf<mach_task_basic_info>() / sizeOf<IntVar>()).convert()
    val result = task_info(
        mach_task_self_,
        MACH_TASK_BASIC_INFO.convert(),
        info.ptr.reinterpret(),
        count.ptr
    )
    if (result == KERN_SUCCESS) (info.resident_size / (1024uL * 1024uL)).toLong() else null
}
