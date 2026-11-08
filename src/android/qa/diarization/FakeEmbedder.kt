package com.bimvo.app.diarization
// Deterministic inference seam: tests algorithm only, never claims to test a neural model.
class SpeakerEmbedder(val at: (Float, Float) -> FloatArray) {
    var cancelled = false
    fun checkCancellation() { if (cancelled) throw java.io.InterruptedIOException("cancelled") }
    fun embedAll(samples: FloatArray, sampleRate: Int, ranges: List<Pair<Float, Float>>, onProgress: (Float) -> Unit): List<FloatArray> {
        checkCancellation()
        return ranges.map { at(it.first, it.second) }.also { onProgress(1f) }
    }
}
data class SpeakerTurn(val start: Float, val end: Float, val speaker: Int)
data class AsrResult(val text: String, val tokens: Array<String>, val timestamps: FloatArray)
data class Utterance(val speakerId: String, val startMs: Long, val endMs: Long, val text: String)
