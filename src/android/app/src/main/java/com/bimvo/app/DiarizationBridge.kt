package com.bimvo.app

import android.content.Context
import com.bimvo.app.diarization.*
import com.bimvo.app.diarization.SpeakerClustering as GlobalClustering
import java.io.File
import java.util.function.BooleanSupplier
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToLong

/** BIMVO storage/PCM adapter; clustering and word assembly come from External diarization. */
internal object DiarizationBridge {
    @JvmStatic fun plan(context: Context, pcm: File, cancelled: BooleanSupplier,
                        progress: SpeakerDiarization.Progress): List<ChunkPlanner.Chunk> {
        val speech = VadEngine(context.assets, cancelled).segments(pcm, 16000, onProgress = {
            progress.update("vad", 15 + (it * 13).toInt())
        })
        if (speech.isEmpty()) throw java.io.IOException("В записи не обнаружена речь")
        val total = pcm.length() / 2
        return speech.mapIndexed { i, s ->
            val start = (s.start * 16000).roundToLong().coerceIn(0, total)
            val end = (s.end * 16000).roundToLong().coerceIn(start, total)
            ChunkPlanner.Chunk(i, start, end, SpeakerDiarization.VERSION, "speaker_unknown")
        }.filter { it.end > it.start }
    }

    @JvmStatic fun encodeWords(result: AsrResult, start: Long, end: Long): String {
        val words = WordAligner.fromAsr(result, start / 16000f, end / 16000f)
        val a = JSONArray()
        for (w in words) {
            val s = w.start.coerceIn(start / 16000f, end / 16000f)
            val e = w.end.coerceIn(s, end / 16000f)
            if (w.text.isNotBlank() && e > s) a.put(JSONObject().put("text", w.text)
                .put("start", s.toDouble()).put("end", e.toDouble()).put("turn", w.turnStart))
        }
        return a.toString()
    }

    @JvmStatic fun decodeWords(json: String): List<Word> {
        val a = JSONArray(json)
        return (0 until a.length()).map { i ->
            val w = a.getJSONObject(i)
            Word(w.getString("text"), w.getDouble("start").toFloat(),
                w.getDouble("end").toFloat(), w.optBoolean("turn"))
        }
    }

    @JvmStatic fun diarize(context: Context, pcm: File, chunks: List<ChunkPlanner.Chunk>,
                          words: List<Word>, cancelled: BooleanSupplier,
                          progress: SpeakerDiarization.Progress): List<Utterance> {
        if (words.isEmpty()) throw java.io.IOException("Не удалось распознать слова в записи")
        val embedder = SpeakerEmbedder(context.assets, pcm, cancelled)
        val turns = try {
            GlobalClustering(embedder).run(floatArrayOf(), 16000,
                chunks.map { GlobalClustering.Chunk(it.start / 16000f, it.end / 16000f) },
                words.filter { it.turnStart }.map { it.start - 0.02f }, emptyList(), 0,
                onProgress = { embedder.checkCancellation(); progress.update("speakers", 70 + (it * 28).toInt()) })
        } finally { embedder.release() }
        if (cancelled.asBoolean) throw java.io.InterruptedIOException("Обработка отменена")
        val assembled = TranscriptAssembler.assemble(words, turns)
        val durationMs = pcm.length() / 32
        return assembled.utterances.mapIndexed { index, u ->
            val s = u.startMs.coerceIn(0, durationMs - 1)
            Utterance(index, u.speakerId, s, u.endMs.coerceIn(s + 1, durationMs), u.text)
        }
    }
}
