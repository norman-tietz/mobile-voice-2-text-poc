package ai.healthcarepoc.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.concurrent.thread

actual class AudioCapture {
    actual val sampleRateHz = 16_000
    private var record: AudioRecord? = null
    private var recordingThread: Thread? = null
    @Volatile private var isRecording = false

    @SuppressLint("MissingPermission")
    actual fun start(onSamples: (FloatArray) -> Unit) {
        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(minBufferSize, sampleRateHz / 5) // ~100ms floor

        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        record = audioRecord
        isRecording = true
        debugLog("AudioCapture.start: bufferSize=$bufferSize minBufferSize=$minBufferSize audioRecordState=${audioRecord.state}")
        audioRecord.startRecording()
        debugLog("AudioCapture.start: startRecording() called, recordingState=${audioRecord.recordingState}")

        recordingThread = thread {
            debugLog("AudioCapture: recording thread started")
            val shortBuffer = ShortArray(bufferSize / 2)
            var bufferCount = 0
            while (isRecording) {
                val read = audioRecord.read(shortBuffer, 0, shortBuffer.size)
                if (read > 0) {
                    bufferCount++
                    if (bufferCount == 1 || bufferCount % 20 == 0) {
                        debugLog("AudioCapture: buffer #$bufferCount, read=$read samples")
                    }
                    val floatBuffer = FloatArray(read) { i -> shortBuffer[i] / 32768.0f }
                    onSamples(floatBuffer)
                } else {
                    debugLog("AudioCapture: read() returned $read (non-positive/error code)")
                }
            }
            debugLog("AudioCapture: recording thread exiting loop, isRecording=$isRecording, totalBuffers=$bufferCount")
        }
    }

    actual fun stop() {
        debugLog("AudioCapture.stop: entered, flipping isRecording=false")
        isRecording = false
        val t0 = System.currentTimeMillis()
        recordingThread?.join()
        debugLog("AudioCapture.stop: recordingThread.join() returned after ${System.currentTimeMillis() - t0}ms")
        recordingThread = null
        record?.stop()
        record?.release()
        record = null
        debugLog("AudioCapture.stop: done")
    }
}
