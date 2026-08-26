package ai.healthcarepoc.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private class FakeVad(
    private val segmentsPerCall: MutableList<List<ClosedFloatingPointRange<Float>>>
) : VoiceActivityDetector {
    val fedChunks = mutableListOf<FloatArray>()
    var resetCalls = 0

    override fun feed(samples: FloatArray) {
        fedChunks.add(samples)
    }

    override fun segments(minSilenceDurationMs: Int): List<ClosedFloatingPointRange<Float>> =
        if (segmentsPerCall.isNotEmpty()) segmentsPerCall.removeAt(0) else emptyList()

    override fun resetState() {
        resetCalls++
    }
}

class SpeechSegmenterTest {

    private val sampleRate = 16_000

    // 100ms chunks @ 16kHz = 1600 samples, matching AudioCapture's real chunk size.
    private fun chunk(value: Float): FloatArray = FloatArray(1600) { value }

    @Test
    fun `accept returns nothing while the VAD has not closed any segment`() {
        val vad = FakeVad(mutableListOf(emptyList(), emptyList()))
        val segmenter = SpeechSegmenter(vad, sampleRate)

        val result1 = segmenter.accept(chunk(0.5f))
        val result2 = segmenter.accept(chunk(0.5f))

        assertEquals(emptyList(), result1)
        assertEquals(emptyList(), result2)
    }

    @Test
    fun `accept returns a newly closed segment sliced from the buffered samples`() {
        // Two 100ms chunks fed (200ms total = 3200 samples). On the second accept()
        // call, the VAD reports one closed segment spanning the first 100ms (0.0-0.1s
        // = samples 0 until 1600) - i.e. only the first chunk's content.
        val vad = FakeVad(mutableListOf(emptyList(), listOf(0.0f..0.1f)))
        val segmenter = SpeechSegmenter(vad, sampleRate)
        val firstChunk = chunk(1.0f)
        val secondChunk = chunk(2.0f)

        segmenter.accept(firstChunk)
        val result = segmenter.accept(secondChunk)

        assertEquals(1, result.size)
        assertEquals(firstChunk.toList(), result[0].toList())
    }

    @Test
    fun `accept does not re-emit a segment already returned in an earlier call`() {
        // First call closes segment [0.0, 0.1]; second call's VAD result still includes
        // that same segment (as whisper_vad_segments_from_probs recomputes from the
        // whole trace each time) plus one new one [0.1, 0.2] - only the new one should
        // come back out.
        val vad = FakeVad(mutableListOf(listOf(0.0f..0.1f), listOf(0.0f..0.1f, 0.1f..0.2f)))
        val segmenter = SpeechSegmenter(vad, sampleRate)
        val firstChunk = chunk(1.0f)
        val secondChunk = chunk(2.0f)

        val firstResult = segmenter.accept(firstChunk)
        val secondResult = segmenter.accept(secondChunk)

        assertEquals(1, firstResult.size)
        assertEquals(1, secondResult.size)
        assertEquals(secondChunk.toList(), secondResult[0].toList())
    }

    @Test
    fun `flush returns the still-open tail after the last emitted segment`() {
        // Real whisper_vad_segments_from_probs() recomputes cumulatively from the whole
        // trace each call, so a segment already closed keeps reappearing in later calls
        // even when nothing new has closed - the fake mirrors that (same list twice)
        // rather than shrinking back to empty, which a real VAD would never do.
        val vad = FakeVad(mutableListOf(listOf(0.0f..0.1f), listOf(0.0f..0.1f)))
        val segmenter = SpeechSegmenter(vad, sampleRate)
        val firstChunk = chunk(1.0f)
        val secondChunk = chunk(2.0f)

        segmenter.accept(firstChunk) // closes [0.0, 0.1] = firstChunk
        segmenter.accept(secondChunk) // no new closed segment; secondChunk stays pending

        val tail = segmenter.flush()

        assertEquals(secondChunk.toList(), tail!!.toList())
    }

    @Test
    fun `flush returns null when everything has already been emitted`() {
        val vad = FakeVad(mutableListOf(listOf(0.0f..0.1f)))
        val segmenter = SpeechSegmenter(vad, sampleRate)

        segmenter.accept(chunk(1.0f)) // closes exactly the one chunk fed so far

        assertNull(segmenter.flush())
    }

    @Test
    fun `reset clears buffered state and resets the VAD`() {
        val vad = FakeVad(mutableListOf(emptyList(), emptyList()))
        val segmenter = SpeechSegmenter(vad, sampleRate)
        segmenter.accept(chunk(1.0f))

        segmenter.reset()

        assertEquals(1, vad.resetCalls)
        assertNull(segmenter.flush())
    }
}
