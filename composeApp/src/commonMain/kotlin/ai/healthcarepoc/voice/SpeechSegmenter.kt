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
    // Hard cap on a single buffered run, independent of the negThreshold/threshold hysteresis
    // below. Sustained ambient noise (HVAC, café, corridor chatter) can sit in the ambiguous
    // band [negThreshold, threshold) indefinitely, in which case trailingSilenceMs never
    // advances and neither hysteresis branch ever fires - without this cap pendingSamples would
    // grow for the entire recording. 30s also matches whisper.cpp's own context window.
    private val maxSegmentDurationMs: Int = 30_000,
    private val threshold: Float = 0.5f,
    // Confirmed on-device (2026-08-26): whisper.cpp's own 0.35 default, tuned for its
    // whole-file batch analysis, is too easily satisfied by per-100ms-chunk classification -
    // natural volume dips *within* continuous real speech (unstressed syllables, consonants)
    // routinely read below 0.35, so the segmenter mistook "still talking, just quieter" for
    // "stopped talking" and cut mid-sentence. Lowered so only chunks the model is genuinely
    // confident are silent count toward the pause timer.
    private val negThreshold: Float = 0.15f
) {
    // Chunks are accumulated by reference and concatenated into one FloatArray only at
    // finalize() - a mutableListOf<Float> would box every sample twice per chunk (once via
    // toList(), once into the ArrayList's backing array) on this coroutine's hot path, which is
    // explicitly documented (see consumerJob in App.kt) as needing to never fall behind.
    private val pendingChunks = mutableListOf<FloatArray>()
    private var pendingSampleCount = 0

    // Whether any chunk in the current pending buffer crossed `threshold`. Doubles as "is the
    // current run in speech" - a silence period that never had real speech in it is dropped
    // instead of finalized, so the Transcriber stage never sees a purely-silent buffer (avoids
    // Whisper hallucinating text for dead air).
    private var inSpeech = false
    private var trailingSilenceMs = 0

    // Feed one capture chunk. Returns a finalized segment if a speech run just ended
    // (minSilenceDurationMs of trailing below-negThreshold audio), else empty.
    fun accept(samples: FloatArray): List<FloatArray> {
        pendingChunks.add(samples)
        pendingSampleCount += samples.size
        val prob = vad.speechProbability(samples)
        val chunkDurationMs = (samples.size * 1000) / sampleRateHz

        if (prob >= threshold) {
            inSpeech = true
            trailingSilenceMs = 0
        } else if (prob < negThreshold) {
            trailingSilenceMs += chunkDurationMs
        }
        // between negThreshold and threshold: ambiguous chunk, leave trailingSilenceMs as-is
        val bufferedDurationMs = (pendingSampleCount * 1000) / sampleRateHz
        debugLog(
            "SpeechSegmenter.accept: prob=$prob, inSpeech=$inSpeech, " +
                "trailingSilenceMs=$trailingSilenceMs, pendingSamples=$pendingSampleCount"
        )

        // Tracked separately so metrics can tell a normal pause-triggered cut apart from the
        // duration cap kicking in (the latter should be rare - frequent cap hits on-device
        // would mean the ambiguous-band problem is still showing up in practice).
        val silenceTriggered = trailingSilenceMs >= minSilenceDurationMs
        val capTriggered = bufferedDurationMs >= maxSegmentDurationMs
        if (inSpeech && (silenceTriggered || capTriggered)) {
            debugLog(
                "SpeechSegmenter.accept: finalizing segment (silenceTriggered=$silenceTriggered, " +
                    "capTriggered=$capTriggered, bufferedDurationMs=$bufferedDurationMs)"
            )
            return finalize()
        }
        if (!inSpeech && (silenceTriggered || capTriggered)) {
            // Long silence, or long ambiguous-but-never-speech noise, before any speech started -
            // drop it so pendingSamples doesn't grow unboundedly while nothing is being said.
            debugLog(
                "SpeechSegmenter.accept: dropping silence-only buffer (silenceTriggered=$silenceTriggered, " +
                    "capTriggered=$capTriggered, bufferedDurationMs=$bufferedDurationMs)"
            )
            clearPending()
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
        clearPending()
        inSpeech = false
        trailingSilenceMs = 0
        vad.resetState()
    }

    private fun clearPending() {
        pendingChunks.clear()
        pendingSampleCount = 0
    }

    private fun finalize(): List<FloatArray> {
        // Capture before clearing below - inSpeech doubles as "was there real speech in this
        // run", mirroring the old hadSpeech/inSpeech split (see the field comment above).
        val wasInSpeech = inSpeech
        inSpeech = false
        trailingSilenceMs = 0
        if (!wasInSpeech) {
            debugLog("SpeechSegmenter.finalize: no speech in pending buffer, dropping it")
            clearPending()
            return emptyList()
        }
        val segment = FloatArray(pendingSampleCount)
        var offset = 0
        for (chunk in pendingChunks) {
            chunk.copyInto(segment, offset)
            offset += chunk.size
        }
        clearPending()
        val durationMs = (segment.size * 1000) / sampleRateHz
        debugLog("SpeechSegmenter.finalize: emitting segment of ${segment.size} samples (${durationMs}ms)")
        return listOf(segment)
    }
}
