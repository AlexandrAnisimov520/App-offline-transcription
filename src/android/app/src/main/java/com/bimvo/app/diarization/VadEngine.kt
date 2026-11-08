package com.bimvo.app.diarization
import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
class VadEngine(private val assets: AssetManager, private val cancelled: java.util.function.BooleanSupplier) {

    data class Params(
        val threshold: Float = 0.4f,
        val minSilence: Float = 0.25f,
        val minSpeech: Float = 0.15f,
        val maxSpeech: Float = 20f,
    )

    @Synchronized
    fun segments(pcm: java.io.File, sampleRate: Int, p: Params = Params(), onProgress: (Float) -> Unit = {}): List<SpeakerClustering.Chunk> {
        val vad = Vad(
            assets,
            VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = "models/vad/silero_vad.onnx",
                    threshold = p.threshold,
                    minSilenceDuration = p.minSilence,
                    minSpeechDuration = p.minSpeech,
                    windowSize = WINDOW,
                    maxSpeechDuration = p.maxSpeech,
                ),
                sampleRate = sampleRate,
                numThreads = 1,
                provider = "cpu",
            ),
        )
        val total = pcm.length() / 2
        val out = ArrayList<SpeakerClustering.Chunk>()
        fun drain() {
            while (!vad.empty()) {
                val seg = vad.front()
                val s = seg.start.toFloat() / sampleRate
                out += SpeakerClustering.Chunk(s, s + seg.samples.size.toFloat() / sampleRate)
                vad.pop()
            }
        }
        try {
            // Кормим строго по одному окну (см. FEED)
            var i = 0L
            java.io.RandomAccessFile(pcm, "r").use { audio ->
            while (i < total) {
                val end = minOf(total, i + FEED)
                if (cancelled.asBoolean) throw java.io.InterruptedIOException("Обработка отменена")
                val wave = FloatArray((end-i).toInt()) { java.lang.Short.reverseBytes(audio.readShort()) / 32768f }
                vad.acceptWaveform(wave)
                drain()
                i = end
                if ((i / WINDOW) % 64 == 0L) onProgress(i.toFloat() / total)
            }
            }
            vad.flush()
            drain()
        } finally {
            vad.release()
        }
        return out
    }

    private companion object {
        const val WINDOW = 512
        /** Ровно одно окно: при подаче больших кусков VAD в sherpa-onnx перестаёт резать речь. */
        const val FEED = WINDOW
    }
}

