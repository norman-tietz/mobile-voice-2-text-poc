package ai.healthcarepoc.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.concurrent.thread

actual class AudioCapture {
    private val sampleRate = 16_000
    private var record: AudioRecord? = null
    private var recordingThread: Thread? = null
    @Volatile private var isRecording = false

    @SuppressLint("MissingPermission")
    actual fun start(onSamples: (FloatArray) -> Unit) {
        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(minBufferSize, sampleRate / 5) // ~100ms floor

        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        record = audioRecord
        isRecording = true
        audioRecord.startRecording()

        recordingThread = thread {
            val shortBuffer = ShortArray(bufferSize / 2)
            while (isRecording) {
                val read = audioRecord.read(shortBuffer, 0, shortBuffer.size)
                if (read > 0) {
                    val floatBuffer = FloatArray(read) { i -> shortBuffer[i] / 32768.0f }
                    onSamples(floatBuffer)
                }
            }
        }
    }

    actual fun stop() {
        isRecording = false
        recordingThread?.join()
        recordingThread = null
        record?.stop()
        record?.release()
        record = null
    }
}
