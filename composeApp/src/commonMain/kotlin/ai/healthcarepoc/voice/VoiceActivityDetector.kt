package ai.healthcarepoc.voice

// Thin seam over whisper.cpp's streaming VAD (whisper_vad_detect_speech_no_reset +
// whisper_vad_segments_from_probs) so the segmentation policy (SpeechSegmenter) can be
// unit-tested against a fake, without needing the real VAD model.
interface VoiceActivityDetector {
    // Feed newly captured samples; appends to the VAD's running trace.
    fun feed(samples: FloatArray)

    // Segment boundaries (seconds, relative to the last resetState() call) that can
    // currently be derived from everything fed so far. A segment only appears once its
    // end has been determined by minSilenceDurationMs of trailing low-probability audio
    // - the in-progress trailing segment (still being spoken) does not appear until it
    // closes.
    fun segments(minSilenceDurationMs: Int): List<ClosedFloatingPointRange<Float>>

    // Clears VAD state between recordings.
    fun resetState()
}
