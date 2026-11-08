package com.bimvo.app.diarization
fun main() {
    val e = SpeakerEmbedder { start, _ -> if (start < 6f || start >= 20f) floatArrayOf(1f, 0f) else floatArrayOf(0f, 1f) }
    val alg = SpeakerClustering(e)
    val speech = listOf(SpeakerClustering.Chunk(0f, 6f), SpeakerClustering.Chunk(8f, 14f), SpeakerClustering.Chunk(20f, 26f))
    val turns = alg.run(floatArrayOf(), 16000, speech, emptyList(), emptyList(), 0)
    check(turns.map { it.speaker }.toSet().size == 2)
    check(turns.first().speaker == turns.last().speaker)
    check(turns.sumOf { (it.end - it.start).toDouble() } == 18.0)
    check(turns.zipWithNext().all { (a,b) -> a.end <= b.start })
    println("PASS: two speakers, returning speaker, full speech coverage, disjoint intervals")
    val short = alg.run(floatArrayOf(), 16000, listOf(SpeakerClustering.Chunk(0f, .3f)), emptyList(), emptyList(), 0)
    check(short.size == 1 && short.single().speaker == 0)
    check(alg.run(floatArrayOf(), 16000, emptyList(), emptyList(), emptyList(), 0).isEmpty())
    check(alg.run(floatArrayOf(), 16000, speech, emptyList(), emptyList(), 1).all { it.speaker == 0 })
    println("PASS: short speech, empty speech, fixed one speaker")
    val words = WordAligner.fromAsr(AsrResult("Привет. Hello!", arrayOf("▁При", "вет", ".", "▁Hello", "!"), floatArrayOf(.1f,.3f,.4f,8.2f,8.5f)), 0f, 14f)
    check(words.size == 2 && words[0].text == "Привет." && words[1].text == "Hello!")
    val result = TranscriptAssembler.assemble(words, turns)
    check(result.speakerCount == 2 && result.utterances.size == 2)
    check(result.utterances.map { it.text }.joinToString(" ") == "Привет. Hello!")
    check(result.utterances.all { it.startMs >= 0 && it.endMs > it.startMs })
    check(result.wordTimes.size == result.utterances.size)
    check(WordAligner.fromAsr(AsrResult("one two", emptyArray(), floatArrayOf()), 3f, 5f).size == 2)
    println("PASS: Cyrillic/English tokens, punctuation, speaker assignment, timestamps, fallback")
    val p = SpeakerClustering.Params()
    check(SpeakerClustering.mergeThresholdFor(20f,p) == .65f)
    check(SpeakerClustering.mergeThresholdFor(120f,p) == .9f)
    check(kotlin.math.abs(SpeakerClustering.mergeThresholdFor(70f,p) - .775f) < .00001f)
    e.cancelled = true
    try { alg.run(floatArrayOf(), 16000, speech, emptyList(), emptyList(), 0); error("cancel ignored") }
    catch (_: java.io.InterruptedIOException) { }
    println("PASS: adaptive threshold and cancellation")
}
