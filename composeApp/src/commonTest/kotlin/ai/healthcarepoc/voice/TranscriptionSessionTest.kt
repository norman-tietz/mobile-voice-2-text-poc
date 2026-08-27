package ai.healthcarepoc.voice

import kotlin.test.Test
import kotlin.test.assertEquals

private class FakeTranscriber(private val responses: MutableList<String>) : Transcriber {
    val callArgs = mutableListOf<FloatArray>()
    var resetContextCalls = 0
    override fun transcribe(samples: FloatArray): String {
        callArgs.add(samples)
        return responses.removeAt(0)
    }
    override fun resetContext() {
        resetContextCalls++
    }
}

class TranscriptionSessionTest {

    private val sampleRate = 16_000

    private fun chunk(ms: Int, value: Float): FloatArray {
        val n = sampleRate * ms / 1000
        return FloatArray(n) { value }
    }

    @Test
    fun `no segments before any transcribeSegment call`() {
        val transcriber = FakeTranscriber(mutableListOf())
        val session = TranscriptionSession(transcriber)

        assertEquals(emptyList(), session.segments)
    }

    @Test
    fun `transcribeSegment appends the transcribed text to segments`() {
        val transcriber = FakeTranscriber(mutableListOf("hallo welt"))
        val session = TranscriptionSession(transcriber)

        session.transcribeSegment(chunk(100, 0.5f))

        assertEquals(listOf("hallo welt"), session.segments)
        assertEquals(1, transcriber.callArgs.size)
    }

    @Test
    fun `multiple transcribeSegment calls accumulate in order`() {
        val transcriber = FakeTranscriber(mutableListOf("erster satz", "zweiter satz"))
        val session = TranscriptionSession(transcriber)

        session.transcribeSegment(chunk(100, 0.5f))
        session.transcribeSegment(chunk(100, 0.7f))

        assertEquals(listOf("erster satz", "zweiter satz"), session.segments)
    }

    @Test
    fun `stop resets the transcriber context`() {
        val transcriber = FakeTranscriber(mutableListOf())
        val session = TranscriptionSession(transcriber)

        session.stop()

        assertEquals(1, transcriber.resetContextCalls)
    }

    @Test
    fun `stop clears finalized segments so they don't accumulate across recordings`() {
        val transcriber = FakeTranscriber(mutableListOf("erster satz"))
        val session = TranscriptionSession(transcriber)
        session.transcribeSegment(chunk(100, 0.5f))

        session.stop()

        assertEquals(emptyList(), session.segments)
    }
}
