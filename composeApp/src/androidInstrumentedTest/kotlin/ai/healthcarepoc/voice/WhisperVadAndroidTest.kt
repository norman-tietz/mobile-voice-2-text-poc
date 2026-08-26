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
        // streaming feed()/segments() path this test exists to verify, not a single
        // whole-file call.
        val chunkSize = 1600
        var offset = 0
        while (offset < samples.size) {
            val end = minOf(offset + chunkSize, samples.size)
            vad.feed(samples.copyOfRange(offset, end))
            offset = end
        }

        val segments = vad.segments(minSilenceDurationMs = 500)
        vad.release()

        assertTrue("expected at least one speech segment, got none", segments.isNotEmpty())
        segments.forEach { range ->
            assertTrue("segment end (${range.endInclusive}) should be after start (${range.start})",
                range.endInclusive > range.start)
        }
    }
}
