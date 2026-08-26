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

    // Whether any chunk in the current pending buffer crossed `threshold`. Doubles as "is the
    // current run in speech" - a silence period that never had real speech in it is dropped
    // instead of finalized, so the Transcriber stage never sees a purely-silent buffer (avoids
    // Whisper hallucinating text for dead air).
    private var inSpeech = false
    private var trailingSilenceMs = 0

    // Feed one capture chunk. Returns a finalized segment if a speech run just ended
    // (minSilenceDurationMs of trailing below-negThreshold audio), else empty.
    fun accept(samples: FloatArray): List<FloatArray> {
        pendingSamples.addAll(samples.toList())
        val prob = vad.speechProbability(samples)
        val chunkDurationMs = (samples.size * 1000) / sampleRateHz

        if (prob >= threshold) {
            inSpeech = true
            trailingSilenceMs = 0
        } else if (prob < negThreshold) {
            trailingSilenceMs += chunkDurationMs
        }
        // between negThreshold and threshold: ambiguous chunk, leave trailingSilenceMs as-is
        debugLog(
            "SpeechSegmenter.accept: prob=$prob, inSpeech=$inSpeech, " +
                "trailingSilenceMs=$trailingSilenceMs, pendingSamples=${pendingSamples.size}"
        )

        if (inSpeech && trailingSilenceMs >= minSilenceDurationMs) {
            debugLog("SpeechSegmenter.accept: trailing silence threshold reached, finalizing segment")
            return finalize()
        }
        if (!inSpeech && trailingSilenceMs >= minSilenceDurationMs) {
            // Long silence before any speech started - drop it so pendingSamples doesn't
            // grow unboundedly while nothing is being said.
            debugLog("SpeechSegmenter.accept: dropping silence-only buffer (no speech yet)")
            pendingSamples.clear()
            trailingSilenceMs = 0
        }
        return emptyList()
    }

    // Force-finalizes whatever's pending (end of recording, no pause reached). Returns
    // null if there's no pending speech.
    fun flush(): FloatArray? {
        if (!inSpeech) {
            debugLog("SpeechSegmenter.flush: no pending speech, nothing to flush")
            return null
        }
        debugLog("SpeechSegmenter.flush: finalizing pending speech run")
        return finalize().firstOrNull()
    }

    // Clears state between recordings.
    fun reset() {
        debugLog("SpeechSegmenter.reset: clearing buffer/VAD state")
        pendingSamples.clear()
        inSpeech = false
        trailingSilenceMs = 0
        vad.resetState()
    }

    private fun finalize(): List<FloatArray> {
        // Capture before clearing below - inSpeech doubles as "was there real speech in this
        // run", mirroring the old hadSpeech/inSpeech split (see the field comment above).
        val wasInSpeech = inSpeech
        inSpeech = false
        trailingSilenceMs = 0
        if (!wasInSpeech) {
            debugLog("SpeechSegmenter.finalize: no speech in pending buffer, dropping it")
            pendingSamples.clear()
            return emptyList()
        }
        val segment = pendingSamples.toFloatArray()
        pendingSamples.clear()
        debugLog("SpeechSegmenter.finalize: emitting segment of ${segment.size} samples")
        return listOf(segment)
    }
}
