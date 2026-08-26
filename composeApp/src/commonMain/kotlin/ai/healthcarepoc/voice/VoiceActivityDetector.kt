package ai.healthcarepoc.voice

// Thin seam over whisper.cpp's VAD so the segmentation policy (SpeechSegmenter) can be
// unit-tested against a fake, without needing the real VAD model. whisper_vad_* does not
// support accumulating a probability trace across calls - each call to
// whisper_vad_detect_speech_no_reset() overwrites the context's probability buffer with
// only that call's chunk (see docs/superpowers/specs/2026-08-25-vad-segmentation-design.md,
// "Correction" section) - so this is a per-chunk classifier, not a segment-boundary deriver.
// The model's recurrent hidden state does carry forward across calls even though the
// probability output doesn't, so results are still informed by everything fed before.
interface VoiceActivityDetector {
    // Speech probability [0, 1] for one capture chunk - the max across the chunk's
    // sub-windows, not an average, so a short loud syllable in an otherwise-quiet chunk
    // isn't diluted away.
    fun speechProbability(samples: FloatArray): Float

    // Clears VAD state between recordings.
    fun resetState()
}
