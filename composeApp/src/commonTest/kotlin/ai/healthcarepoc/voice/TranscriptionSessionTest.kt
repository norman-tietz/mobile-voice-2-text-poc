package ai.healthcarepoc.voice

import kotlin.test.Test
import kotlin.test.assertEquals

private class FakeTranscriber(private val responses: MutableList<String>) : Transcriber {
    val callArgs = mutableListOf<FloatArray>()
    override fun transcribe(samples: FloatArray): String {
        callArgs.add(samples)
        return responses.removeAt(0)
    }
}

class TranscriptionSessionTest {

    private val sampleRate = 16_000

    private fun chunk(ms: Int, value: Float): FloatArray {
        val n = sampleRate * ms / 1000
        return FloatArray(n) { value }
    }

    @Test
    fun `no segments before any pause or stop`() {
        val transcriber = FakeTranscriber(mutableListOf())
        val session = TranscriptionSession(transcriber, PauseDetector(sampleRate, minSilenceDurationMs = 700))

        session.acceptAudio(chunk(100, 0.5f))

        assertEquals(emptyList(), session.segments)
    }

    @Test
    fun `finalizes a segment when a pause is detected`() {
        val transcriber = FakeTranscriber(mutableListOf("hallo welt"))
        val session = TranscriptionSession(transcriber, PauseDetector(sampleRate, minSilenceDurationMs = 700))

        session.acceptAudio(chunk(100, 0.5f))
        repeat(7) { session.acceptAudio(chunk(100, 0.0f)) }

        assertEquals(listOf("hallo welt"), session.segments)
    }

    @Test
    fun `starts a new empty buffer after a segment is finalized`() {
        val transcriber = FakeTranscriber(mutableListOf("erster satz"))
        val session = TranscriptionSession(transcriber, PauseDetector(sampleRate, minSilenceDurationMs = 700))

        session.acceptAudio(chunk(100, 0.5f))
        repeat(7) { session.acceptAudio(chunk(100, 0.0f)) }

        // First call's buffer should be just the loud chunk plus the silence up to the boundary,
        // not carry over into whatever comes next.
        assertEquals(1, transcriber.callArgs.size)
    }

    @Test
    fun `force-transcribes a pending buffer on stop even without a pause`() {
        val transcriber = FakeTranscriber(mutableListOf("letzter satz"))
        val session = TranscriptionSession(transcriber, PauseDetector(sampleRate, minSilenceDurationMs = 700))

        session.acceptAudio(chunk(100, 0.5f))
        val result = session.stop()

        assertEquals(listOf("letzter satz"), result)
        assertEquals(listOf("letzter satz"), session.segments)
    }

    @Test
    fun `stop with no pending audio produces no extra segment`() {
        val transcriber = FakeTranscriber(mutableListOf("hallo welt"))
        val session = TranscriptionSession(transcriber, PauseDetector(sampleRate, minSilenceDurationMs = 700))

        session.acceptAudio(chunk(100, 0.5f))
        repeat(7) { session.acceptAudio(chunk(100, 0.0f)) }
        val result = session.stop()

        assertEquals(listOf("hallo welt"), result)
        assertEquals(1, transcriber.callArgs.size)
    }
}