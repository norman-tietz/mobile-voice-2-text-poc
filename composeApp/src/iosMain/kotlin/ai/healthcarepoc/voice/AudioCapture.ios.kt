package ai.healthcarepoc.voice

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryRecord
import platform.AVFAudio.setActive
import platform.Foundation.NSError
import platform.Foundation.NSMakeRange

// Nothing in this app ever calls AVAudioSession.setCategory/setActive elsewhere, so the input
// node's format can otherwise be queried (both here and in MainViewController's sample-rate
// probe) before a record-capable session exists - the default SoloAmbient category makes input
// hardware unavailable, which can surface as a 0Hz format or a silently failing engine start.
// Called again on every start() (not just once) since a session can be deactivated between
// recordings by an interruption (a call, another app) - re-asserting it here is the standard
// AVAudioSession pattern of configuring right before you need it, not just once at launch.
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun configureAudioSessionForRecording() {
    val session = AVAudioSession.sharedInstance()
    memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        val categorySet = session.setCategory(AVAudioSessionCategoryRecord, error = error.ptr)
        check(categorySet) { "Failed to set AVAudioSession category: ${error.value?.localizedDescription}" }
    }
    memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        val activated = session.setActive(true, error = error.ptr)
        check(activated) { "Failed to activate AVAudioSession: ${error.value?.localizedDescription}" }
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
actual class AudioCapture {
    private val engine = AVAudioEngine()

    // Queried fresh from the live capture engine at start() below, not from a separate
    // throwaway engine read once at app launch (MainViewController used to do this) - that
    // value could go stale if the audio route changed (e.g. a Bluetooth headset connecting)
    // between launch and the user actually tapping Record, silently mis-sizing every resample.
    private var _sampleRateHz: Int = 0
    actual val sampleRateHz: Int get() = _sampleRateHz

    actual fun start(onSamples: (FloatArray) -> Unit) {
        configureAudioSessionForRecording()

        val inputNode = engine.inputNode
        val format = inputNode.outputFormatForBus(0u)
        _sampleRateHz = format.sampleRate.toInt()

        inputNode.installTapOnBus(
            bus = 0u,
            bufferSize = (format.sampleRate / 10).toUInt(), // ~100ms
            format = format
        ) { buffer: AVAudioPCMBuffer?, _ ->
            val channelData = buffer?.floatChannelData?.get(0) ?: return@installTapOnBus
            val frameLength = buffer.frameLength.toInt()
            val samples = FloatArray(frameLength)
            for (i in 0 until frameLength) {
                samples[i] = channelData[i]
            }
            onSamples(samples)
        }

        engine.prepare()
        // The discarded Bool/NSError from engine.startAndReturnError(null) previously let a
        // failed start (e.g. a still-misconfigured session) succeed silently: no tap ever fires,
        // uiState stays Recording, and Stop produces an empty transcript with no error shown.
        // Checking it and throwing lets App.kt's CoroutineExceptionHandler surface a real error.
        memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            val started = engine.startAndReturnError(error.ptr)
            check(started) { "AVAudioEngine failed to start: ${error.value?.localizedDescription}" }
        }
    }

    actual fun stop() {
        engine.inputNode.removeTapOnBus(0u)
        engine.stop()
    }
}
