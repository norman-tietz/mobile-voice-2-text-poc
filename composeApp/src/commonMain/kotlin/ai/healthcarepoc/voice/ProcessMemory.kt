package ai.healthcarepoc.voice

// Current resident memory (RSS) of this process, in MB, or null if it couldn't be read. This is
// an OS-reported, order-of-magnitude figure - not lab-grade precise (shared/mapped pages, GC
// timing, and other background system activity all nudge it around a bit) - but it's enough to
// see roughly how much the Whisper model adds once loaded, and roughly how much a recording's
// own buffers add on top, without needing a profiler (Instruments/Android Studio Profiler)
// attached. See its call sites in App.kt.
expect fun currentResidentMemoryMb(): Long?
