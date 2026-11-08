package com.bimvo.app.diarization
import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
internal val numThreads: Int get() = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
object ModelPaths { const val EMBEDDING = "models/diarization/embedding.onnx" }
data class AsrResult(val text: String, val tokens: Array<String>, val timestamps: FloatArray)
data class Utterance(val speakerId: String, val startMs: Long, val endMs: Long, val text: String)
class SpeakerEmbedder(private val assets: AssetManager, private val pcm: java.io.File, private val cancelled: java.util.function.BooleanSupplier) {

    fun checkCancellation() { if (cancelled.asBoolean || Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Обработка отменена") }

    private var pool: List<SpeakerEmbeddingExtractor>? = null

    @Synchronized
    private fun extractors(): List<SpeakerEmbeddingExtractor> {
        pool?.let { return it }
        val created = ArrayList<SpeakerEmbeddingExtractor>()
        try {
            repeat(numThreads) {
                checkCancellation()
                created += SpeakerEmbeddingExtractor(assets,
                    SpeakerEmbeddingExtractorConfig(model = ModelPaths.EMBEDDING, numThreads = 1, debug = false, provider = "cpu"))
            }
            pool = created
            return created
        } catch (error: Throwable) {
            created.forEach { it.release() }
            throw error
        }
    }

    /** Эмбеддинги для отрезков (секунды); порядок результатов совпадает с порядком отрезков. */
    fun embedAll(
        samples: FloatArray,
        sampleRate: Int,
        ranges: List<Pair<Float, Float>>,
        onProgress: (Float) -> Unit = {},
    ): List<FloatArray> {
        if (ranges.isEmpty()) return emptyList()
        val ex = extractors()
        val out = arrayOfNulls<FloatArray>(ranges.size)
        val next = java.util.concurrent.atomic.AtomicInteger(0)
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val error = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val workers = ex.map { e ->
            Thread {
                try {
                    while (!stop.get()) {
                        checkCancellation()
                        val i = next.getAndIncrement()
                        if (i >= ranges.size) break
                        val (s, t) = ranges[i]
                        val from = (s * sampleRate).toLong().coerceIn(0, pcm.length() / 2)
                        val to = (t * sampleRate).toLong().coerceIn(from, pcm.length() / 2)
                        val stream = e.createStream()
                        try {
                            val wave = java.io.RandomAccessFile(pcm, "r").use { f ->
                                f.seek(from * 2)
                                FloatArray((to - from).toInt()) { java.lang.Short.reverseBytes(f.readShort()) / 32768f }
                            }
                            stream.acceptWaveform(wave, sampleRate)
                            stream.inputFinished()
                            out[i] = e.compute(stream)
                        } finally {
                            stream.release()
                        }
                        val d = done.incrementAndGet()
                        if (d % 8 == 0 || d == ranges.size) onProgress(d.toFloat() / ranges.size)
                    }
                } catch (t: Throwable) {
                    error.compareAndSet(null, t)
                    stop.set(true)
                }
            }.apply { name = "embed"; start() }
        }
        try {
            workers.forEach { it.join() }
        } catch (e: InterruptedException) {
            stop.set(true)
            workers.forEach { it.join() }
            throw e
        }
        error.get()?.let { throw it }
        return out.map { it!! }
    }

    @Synchronized
    fun release() {
        pool?.forEach { it.release() }
        pool = null
    }
}

data class SpeakerTurn(val start: Float, val end: Float, val speaker: Int) {
    val duration: Float get() = end - start
}

