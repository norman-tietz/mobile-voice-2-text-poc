package ai.healthcarepoc.voice

// Real-time VAD-driven replacement for PauseDetector: decides segment boundaries as audio
// arrives, using whisper.cpp's Silero VAD as a per-chunk speech classifier (see
// VoiceActivityDetector) instead of a static RMS threshold. Mirrors whisper.cpp's own
// hysteresis - entering a speech run requires crossing `threshold`; once in one, only
// trailing audio below the lower `negThreshold` counts toward silence - to avoid boundary
// chatter right at the edge. See docs/superpowers/specs/2026-08-25-vad-segmentation-design.md.
class SpeechSegmenter(
    private val vad: VoiceActivityDetector,
    private val sampleRateHz: Int,
    private val minSilenceDurationMs: Int = 500,
    private val threshold: Float = 0.5f,
    private val negThreshold: Float = 0.35f
) {
    private val pendingSamples = mutableListOf<Float>()
    private var inSpeech = false
    private var trailingSilenceMs = 0

    // Whether any chunk in the current pending buffer crossed `threshold`. A silence period
    // that never had real speech in it is dropped instead of finalized, so the Transcriber
    // stage never sees a purely-silent buffer (avoids Whisper hallucinating text for dead air).
    private var hadSpeech = false

    // Feed one capture chunk. Returns a finalized segment if a speech run just ended
    // (minSilenceDurationMs of trailing below-negThreshold audio), else empty.
    fun accept(samples: FloatArray): List<FloatArray> {
        pendingSamples.addAll(samples.toList())
        val prob = vad.speechProbability(samples)
        val chunkDurationMs = (samples.size * 1000) / sampleRateHz

        if (prob >= threshold) {
            inSpeech = true
            hadSpeech = true
            trailingSilenceMs = 0
        } else if (prob < negThreshold) {
            trailingSilenceMs += chunkDurationMs
        }
        // between negThreshold and threshold: ambiguous chunk, leave trailingSilenceMs as-is

        if (inSpeech && trailingSilenceMs >= minSilenceDurationMs) {
            return finalize()
        }
        if (!inSpeech && trailingSilenceMs >= minSilenceDurationMs) {
            // Long silence before any speech started - drop it so pendingSamples doesn't
            // grow unboundedly while nothing is being said.
            pendingSamples.clear()
            trailingSilenceMs = 0
        }
        return emptyList()
    }

    // Force-finalizes whatever's pending (end of recording, no pause reached). Returns
    // null if there's no pending speech.
    fun flush(): FloatArray? {
        if (!hadSpeech) return null
        return finalize().firstOrNull()
    }

    // Clears state between recordings.
    fun reset() {
        pendingSamples.clear()
        inSpeech = false
        trailingSilenceMs = 0
        hadSpeech = false
        vad.resetState()
    }

    private fun finalize(): List<FloatArray> {
        inSpeech = false
        trailingSilenceMs = 0
        if (!hadSpeech) {
            pendingSamples.clear()
            return emptyList()
        }
        val segment = pendingSamples.toFloatArray()
        pendingSamples.clear()
        hadSpeech = false
        return listOf(segment)
    }
}
