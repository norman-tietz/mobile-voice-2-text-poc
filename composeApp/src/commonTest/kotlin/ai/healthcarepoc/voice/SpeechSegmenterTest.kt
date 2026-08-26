package ai.healthcarepoc.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private class FakeVad(private val probsPerCall: MutableList<Float>) : VoiceActivityDetector {
    var resetCalls = 0

    override fun speechProbability(samples: FloatArray): Float =
        if (probsPerCall.isNotEmpty()) probsPerCall.removeAt(0) else 0.0f

    override fun resetState() {
        resetCalls++
    }
}

class SpeechSegmenterTest {

    private val sampleRate = 16_000

    // 100ms chunks @ 16kHz = 1600 samples, matching AudioCapture's real chunk size.
    private fun chunk(value: Float): FloatArray = FloatArray(1600) { value }

    @Test
    fun `accept returns nothing while probability stays below threshold`() {
        val vad = FakeVad(mutableListOf(0.1f, 0.1f))
        val segmenter = SpeechSegmenter(vad, sampleRate, minSilenceDurationMs = 700)

        assertEquals(emptyList(), segmenter.accept(chunk(0.5f)))
        assertEquals(emptyList(), segmenter.accept(chunk(0.5f)))
    }

    @Test
    fun `finalizes a segment after speech then enough trailing low-probability chunks`() {
        val vad = FakeVad((mutableListOf(0.9f) + List(7) { 0.1f }).toMutableList())
        val segmenter = SpeechSegmenter(vad, sampleRate, minSilenceDurationMs = 700)

        segmenter.accept(chunk(1.0f))
        var result: List<FloatArray> = emptyList()
        repeat(7) { result = segmenter.accept(chunk(0.0f)) }

        assertEquals(1, result.size)
    }

    @Test
    fun `drops a silent buffer that never had speech, without emitting it`() {
        val vad = FakeVad(MutableList(7) { 0.1f })
        val segmenter = SpeechSegmenter(vad, sampleRate, minSilenceDurationMs = 700)

        var sawSegment = false
        repeat(7) { if (segmenter.accept(chunk(0.0f)).isNotEmpty()) sawSegment = true }

        assertEquals(false, sawSegment)
        assertNull(segmenter.flush())
    }

    @Test
    fun `stays in speech through an ambiguous chunk between negThreshold and threshold`() {
        // 0.9 enters speech; 0.4 is between negThreshold(0.15) and threshold(0.5) - must NOT
        // count as silence and must NOT exit speech; trailing silence still needs the full
        // duration counted only from genuinely low-probability chunks afterward.
        val vad = FakeVad((mutableListOf(0.9f, 0.4f) + List(7) { 0.1f }).toMutableList())
        val segmenter = SpeechSegmenter(vad, sampleRate, minSilenceDurationMs = 700)

        segmenter.accept(chunk(1.0f)) // 0.9 -> enters speech
        val ambiguous = segmenter.accept(chunk(1.0f)) // 0.4 -> ambiguous, stays in speech
        assertEquals(emptyList(), ambiguous)

        var result: List<FloatArray> = emptyList()
        repeat(7) { result = segmenter.accept(chunk(0.0f)) }
        assertEquals(1, result.size)
    }

    @Test
    fun `forces a finalize once buffered duration hits the cap, even with no trailing silence`() {
        // All chunks after entry stay ambiguous (0.4 is between negThreshold and threshold), so
        // trailingSilenceMs never advances - only the duration cap can end this run.
        val vad = FakeVad((mutableListOf(0.9f) + List(10) { 0.4f }).toMutableList())
        val segmenter = SpeechSegmenter(
            vad, sampleRate, minSilenceDurationMs = 10_000, maxSegmentDurationMs = 500
        )

        segmenter.accept(chunk(1.0f)) // 0.9 -> enters speech, 100ms buffered
        var result: List<FloatArray> = emptyList()
        repeat(4) { result = segmenter.accept(chunk(1.0f)) } // +400ms ambiguous = 500ms buffered

        assertEquals(1, result.size)
        assertEquals(5 * 1600, result[0].size)
    }

    @Test
    fun `drops a pre-speech buffer once it hits the duration cap, without ever entering speech`() {
        // Ambiguous the whole time (never crosses threshold, never drops below negThreshold), so
        // trailingSilenceMs never advances - only the duration cap can drop this buffer.
        val vad = FakeVad(MutableList(10) { 0.4f })
        val segmenter = SpeechSegmenter(
            vad, sampleRate, minSilenceDurationMs = 10_000, maxSegmentDurationMs = 500
        )

        var sawSegment = false
        repeat(5) { if (segmenter.accept(chunk(0.4f)).isNotEmpty()) sawSegment = true }

        assertEquals(false, sawSegment)
        assertNull(segmenter.flush())
    }

    @Test
    fun `flush returns the pending buffer if it had speech`() {
        val vad = FakeVad(mutableListOf(0.9f))
        val segmenter = SpeechSegmenter(vad, sampleRate, minSilenceDurationMs = 700)
        val speechChunk = chunk(1.0f)

        segmenter.accept(speechChunk)
        val tail = segmenter.flush()

        assertEquals(speechChunk.toList(), tail!!.toList())
    }

    @Test
    fun `flush returns null when nothing pending had speech`() {
        val vad = FakeVad(mutableListOf(0.1f))
        val segmenter = SpeechSegmenter(vad, sampleRate, minSilenceDurationMs = 700)

        segmenter.accept(chunk(0.0f))

        assertNull(segmenter.flush())
    }

    @Test
    fun `reset clears state and calls vad resetState`() {
        val vad = FakeVad(mutableListOf(0.9f))
        val segmenter = SpeechSegmenter(vad, sampleRate, minSilenceDurationMs = 700)
        segmenter.accept(chunk(1.0f))

        segmenter.reset()

        assertEquals(1, vad.resetCalls)
        assertNull(segmenter.flush())
    }
}
