package ai.healthcarepoc.voice

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PauseDetectorTest {

    private val sampleRate = 16_000

    private fun loudChunk(sizeMs: Int): FloatArray {
        val n = sampleRate * sizeMs / 1000
        return FloatArray(n) { i -> if (i % 2 == 0) 0.5f else -0.5f }
    }

    private fun silentChunk(sizeMs: Int): FloatArray {
        val n = sampleRate * sizeMs / 1000
        return FloatArray(n) { 0.0f }
    }

    @Test
    fun `does not signal a pause while speech is loud`() {
        val detector = PauseDetector(sampleRate, minSilenceDurationMs = 700)
        repeat(5) {
            assertFalse(detector.accept(loudChunk(100)))
        }
    }

    @Test
    fun `signals a pause once trailing silence exceeds the threshold duration`() {
        val detector = PauseDetector(sampleRate, minSilenceDurationMs = 700)
        assertFalse(detector.accept(loudChunk(100)))
        // Feed 600ms of silence: not yet a pause.
        assertFalse(detector.accept(silentChunk(100)))
        assertFalse(detector.accept(silentChunk(100)))
        assertFalse(detector.accept(silentChunk(100)))
        assertFalse(detector.accept(silentChunk(100)))
        assertFalse(detector.accept(silentChunk(100)))
        assertFalse(detector.accept(silentChunk(100)))
        // 7th 100ms chunk of silence crosses the 700ms threshold.
        assertTrue(detector.accept(silentChunk(100)))
    }

    @Test
    fun `reset clears trailing silence tracking`() {
        val detector = PauseDetector(sampleRate, minSilenceDurationMs = 700)
        repeat(6) { detector.accept(silentChunk(100)) }
        detector.reset()
        // After reset, silence tracking starts over: one more 100ms chunk is not enough.
        assertFalse(detector.accept(silentChunk(100)))
    }
}
