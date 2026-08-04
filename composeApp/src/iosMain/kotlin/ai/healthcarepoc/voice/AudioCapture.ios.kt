package ai.healthcarepoc.voice

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.get
import kotlinx.cinterop.usePinned
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioPCMBuffer
import platform.Foundation.NSMakeRange

@OptIn(ExperimentalForeignApi::class)
actual class AudioCapture {
    private val engine = AVAudioEngine()

    actual fun start(onSamples: (FloatArray) -> Unit) {
        val inputNode = engine.inputNode
        val format = inputNode.outputFormatForBus(0u)

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
        engine.startAndReturnError(null)
    }

    actual fun stop() {
        engine.inputNode.removeTapOnBus(0u)
        engine.stop()
    }
}
