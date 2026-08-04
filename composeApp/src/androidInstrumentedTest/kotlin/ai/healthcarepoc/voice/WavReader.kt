package ai.healthcarepoc.voice

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

fun readWavAsFloatMono16k(input: InputStream): FloatArray {
    val bytes = input.readBytes()
    // Skip the 44-byte canonical WAV header; assumes a 16-bit PCM, 16kHz, mono WAV file.
    val buffer = ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(ByteOrder.LITTLE_ENDIAN)
    val sampleCount = (bytes.size - 44) / 2
    return FloatArray(sampleCount) { buffer.short / 32768.0f }
}