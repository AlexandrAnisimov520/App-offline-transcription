package com.bimvo.app.ml

import android.content.Context
import android.net.Uri
import com.bimvo.app.Utterance
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** File-only inference. Caller owns the background thread and serializes invocations. */
class LocalTranscriber(private val context: Context) {
    enum class Language { RUSSIAN, ENGLISH }

    fun interface Progress { fun update(message: String) }
    data class Result(val turns: List<Utterance>, val durationMs: Long)

    private fun model(name: String): String {
        val dir = File(context.filesDir, "bundled-models").apply { mkdirs() }
        val file = File(dir, name)
        if (!file.isFile) {
            val part = File(dir, "$name.part")
            try {
                context.assets.open("asr/$name").use { input ->
                    part.outputStream().use { output -> input.copyTo(output, 65536) }
                }
                check(part.renameTo(file)) { "Cannot install model $name" }
            } finally { part.delete() }
        }
        return file.absolutePath
    }

    fun transcribe(source: File, language: Language, progress: Progress): Result = runBlocking {
        progress.update("Декодирование аудио")
        val decoded = PcmDecoder.importToWav(context, Uri.fromFile(source))
        try {
            // A bounded baseline: reject beyond the measured working range rather than risk OOM.
            require(decoded.durationMs in 1..600_000) {
                "Текущая сборка поддерживает записи до 10 минут"
            }
            val samples = RandomAccessFile(decoded.wavFile, "r").use { file ->
                file.seek(44)
                val count = ((file.length() - 44) / 2).toInt()
                val result = FloatArray(count)
                val bytes = ByteArray(65536)
                var offset = 0
                while (offset < count) {
                    val length = minOf(bytes.size, (count - offset) * 2)
                    file.readFully(bytes, 0, length)
                    val buffer = ByteBuffer.wrap(bytes, 0, length).order(ByteOrder.LITTLE_ENDIAN)
                    repeat(length / 2) { result[offset++] = buffer.short / 32768f }
                }
                result
            }
            progress.update("Определение говорящих · CAM++")
            val diarizer = OfflineSpeakerDiarization(config = OfflineSpeakerDiarizationConfig(
                segmentation = OfflineSpeakerSegmentationModelConfig(
                    pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(model("segmentation.onnx")),
                    numThreads = 2, provider = "cpu"),
                embedding = SpeakerEmbeddingExtractorConfig(
                    model = model("speaker_embedding_camplus_zh_en.onnx"), numThreads = 2, provider = "cpu"),
                clustering = FastClusteringConfig(numClusters = -1, threshold = 0.7f),
                minDurationOn = 0.15f, minDurationOff = 0.3f))
            // No callback bridge, setConfig or concurrent release across JNI.
            val segments = try { diarizer.process(samples).sortedBy { it.start } }
                finally { diarizer.release() }
            if (segments.isEmpty()) return@runBlocking Result(emptyList(), decoded.durationMs)
            val recognizer = when (language) {
                Language.RUSSIAN -> {
                    progress.update("Загрузка GigaAM")
                    OfflineRecognizer(config = OfflineRecognizerConfig(
                        featConfig = FeatureConfig(sampleRate = 16000, featureDim = 64),
                        modelConfig = OfflineModelConfig(
                            nemo = OfflineNemoEncDecCtcModelConfig(model("gigaam_v3_e2e_ctc_int8.onnx")),
                            tokens = model("gigaam_v3_e2e_ctc_tokens.txt"), numThreads = 2,
                            provider = "cpu", modelType = "nemo_ctc"), decodingMethod = "greedy_search"))
                }
                Language.ENGLISH -> {
                    progress.update("Загрузка Parakeet")
                    OfflineRecognizer(assetManager = context.assets, config = OfflineRecognizerConfig(
                        featConfig = FeatureConfig(sampleRate = 16000, featureDim = 128),
                        modelConfig = OfflineModelConfig(
                            transducer = OfflineTransducerModelConfig(
                                encoder = "asr/parakeet-600m/encoder.int8.onnx",
                                decoder = "asr/parakeet-600m/decoder.int8.onnx",
                                joiner = "asr/parakeet-600m/joiner.int8.onnx"),
                            tokens = "asr/parakeet-600m/tokens.txt", numThreads = 2,
                            provider = "cpu", modelType = "nemo_transducer"), decodingMethod = "greedy_search"))
                }
            }
            val turns = mutableListOf<Utterance>()
            val speakers = linkedMapOf<Int, String>()
            try {
                for ((index, segment) in segments.withIndex()) {
                    progress.update("Распознавание реплики ${index + 1}/${segments.size}")
                    val start = (segment.start * 16000).toInt().coerceIn(0, samples.size)
                    val end = (segment.end * 16000).toInt().coerceIn(start, samples.size)
                    val speaker = speakers.getOrPut(segment.speaker) { "speaker_${speakers.size + 1}" }
                    var from = start
                    while (from < end) {
                        val to = minOf(from + 30 * 16000, end)
                        val stream = recognizer.createStream()
                        val text = try {
                            stream.acceptWaveform(samples.copyOfRange(from, to), 16000)
                            recognizer.decode(stream)
                            recognizer.getResult(stream).text.trim()
                        } finally { stream.release() }
                        if (text.isNotBlank()) turns.add(Utterance(turns.size, speaker,
                            from * 1000L / 16000, to * 1000L / 16000, text))
                        from = to
                    }
                }
            } finally { recognizer.release() }
            Result(turns, decoded.durationMs)
        } finally { decoded.wavFile.delete() }
    }
}
