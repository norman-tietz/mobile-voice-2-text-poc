package ai.healthcarepoc.voice

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assert.assertTrue
import java.io.File

class WhisperVadAndroidTest {

    @Test
    fun detectsSpeechSegmentsInGermanSample() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val modelFile = File(targetContext.cacheDir, "ggml-silero-v6.2.0.bin")
        targetContext.assets.open("models/ggml-silero-v6.2.0.bin").use { input ->
            modelFile.outputStream().use { output -> input.copyTo(output) }
        }

        val vad = WhisperVad(modelFile.absolutePath)
        val samples = readWavAsFloatMono16k(testContext.assets.open("sample-de.wav"))

        // Feed in capture-sized chunks (1600 samples / 100ms @ 16kHz), matching how
        // AudioCapture delivers audio in production - this is what actually exercises the
        // streaming speechProbability() path this test exists to verify, not a single
        // whole-file call. whisper_vad_* is a per-chunk classifier (see
        // VoiceActivityDetector), not a segment-boundary deriver, so this test tracks the
        // max probability seen across all chunks rather than asking the VAD for segments.
        val chunkSize = 1600
        var offset = 0
        var maxProbability = 0f
        while (offset < samples.size) {
            val end = minOf(offset + chunkSize, samples.size)
            val prob = vad.speechProbability(samples.copyOfRange(offset, end))
            maxProbability = maxOf(maxProbability, prob)
            offset = end
        }

        vad.release()

        // whisper.cpp's default speech threshold is 0.5 - assert the real speech sample is
        // actually classified as containing speech somewhere in it.
        assertTrue(
            "expected max speechProbability() >= 0.5 across all chunks, got $maxProbability",
            maxProbability >= 0.5f
        )
    }
}
