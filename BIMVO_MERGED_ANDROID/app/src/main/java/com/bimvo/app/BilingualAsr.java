package com.bimvo.app;

import android.content.Context;
import android.content.res.AssetManager;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig;
import com.k2fsa.sherpa.onnx.SpokenLanguageIdentification;
import com.k2fsa.sherpa.onnx.SpokenLanguageIdentificationConfig;
import com.k2fsa.sherpa.onnx.SpokenLanguageIdentificationWhisperConfig;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/** Routes speaker-aware chunks offline. The language detector closes before ASR model allocation. */
final class BilingualAsr implements AutoCloseable {
    static final String MODEL_VERSION = "GigaAM-v3+Parakeet-600M-v2+Whisper-LID+ERes2NetV2";
    private static final String PLAN = SpeakerDiarization.VERSION + "+lid-v1:";
    private static final String RU = PLAN + "ru";
    private static final String EN = PLAN + "en";
    private static final String ASSETS = "models/parakeet-600m/";
    private static final String LID_ASSETS = "models/whisper-lid/";
    private static final int RATE = ChunkPlanner.SAMPLE_RATE;
    private final OfflineRecognizer recognizer;

    BilingualAsr(Context context) throws IOException {
        AssetManager assets = context.getAssets();
        String encoder = requireAsset(assets, ASSETS + "encoder.int8.onnx");
        String decoder = requireAsset(assets, ASSETS + "decoder.int8.onnx");
        String joiner = requireAsset(assets, ASSETS + "joiner.int8.onnx");
        String tokens = requireAsset(assets, ASSETS + "tokens.txt");

        OfflineTransducerModelConfig transducer = new OfflineTransducerModelConfig();
        transducer.setEncoder(encoder);
        transducer.setDecoder(decoder);
        transducer.setJoiner(joiner);
        OfflineModelConfig model = new OfflineModelConfig();
        model.setTransducer(transducer);
        model.setTokens(tokens);
        model.setModelType("nemo_transducer");
        model.setProvider("cpu");
        model.setNumThreads(2);
        FeatureConfig features = new FeatureConfig();
        features.setSampleRate(RATE);
        features.setFeatureDim(80);
        OfflineRecognizerConfig config = new OfflineRecognizerConfig();
        config.setFeatConfig(features);
        config.setModelConfig(model);
        config.setDecodingMethod("greedy_search");
        recognizer = new OfflineRecognizer(assets, config);
    }

    static boolean hasLanguage(ChunkPlanner.Chunk chunk) {
        return EN.equals(chunk.reason) || RU.equals(chunk.reason);
    }

    static boolean isEnglish(ChunkPlanner.Chunk chunk) {
        return EN.equals(chunk.reason);
    }

    static List<ChunkPlanner.Chunk> assignLanguages(Context context, RandomAccessFile audio,
            List<ChunkPlanner.Chunk> chunks, BooleanSupplier cancelled) throws IOException {
        AssetManager assets = context.getAssets();
        SpokenLanguageIdentificationWhisperConfig model = new SpokenLanguageIdentificationWhisperConfig();
        model.setEncoder(requireAsset(assets, LID_ASSETS + "tiny-encoder.int8.onnx"));
        model.setDecoder(requireAsset(assets, LID_ASSETS + "tiny-decoder.int8.onnx"));
        SpokenLanguageIdentificationConfig config = new SpokenLanguageIdentificationConfig();
        config.setWhisper(model);
        config.setProvider("cpu");
        config.setNumThreads(2);
        SpokenLanguageIdentification detector = new SpokenLanguageIdentification(assets, config);
        String[] labels = new String[chunks.size()];
        try {
            for (int i = 0; i < chunks.size(); i++) {
                SpeakerClustering.check(cancelled);
                ChunkPlanner.Chunk chunk = chunks.get(i);
                if (chunk.end - chunk.start < RATE / 2) continue;
                OfflineStream stream = detector.createStream();
                try {
                    long end = Math.min(chunk.end, chunk.start + 10L * RATE);
                    stream.acceptWaveform(AudioDecoder.readPcm(audio, chunk.start, end), RATE);
                    String label = detector.compute(stream).trim().toLowerCase(Locale.ROOT);
                    if ("ru".equals(label) || "en".equals(label)) labels[i] = label;
                } finally {
                    stream.release();
                }
            }
        } finally {
            detector.release();
        }

        List<ChunkPlanner.Chunk> result = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            ChunkPlanner.Chunk chunk = chunks.get(i);
            String language = labels[i] == null ? nearbyLanguage(chunks, labels, i) : labels[i];
            result.add(new ChunkPlanner.Chunk(chunk.index, chunk.start, chunk.end,
                    "en".equals(language) ? EN : RU, chunk.speakerId));
        }
        return result;
    }

    private static String nearbyLanguage(List<ChunkPlanner.Chunk> chunks, String[] labels, int index) {
        ChunkPlanner.Chunk chunk = chunks.get(index);
        if ("speaker_unknown".equals(chunk.speakerId)) return "ru";
        long nearest = 10L * RATE;
        String language = "ru";
        for (int i = index - 1; i >= 0; i--) {
            ChunkPlanner.Chunk other = chunks.get(i);
            long gap = chunk.start - other.end;
            if (gap >= nearest) break;
            if (labels[i] != null && chunk.speakerId.equals(other.speakerId)) {
                nearest = gap;
                language = labels[i];
                break;
            }
        }
        for (int i = index + 1; i < chunks.size(); i++) {
            ChunkPlanner.Chunk other = chunks.get(i);
            if (other.start - chunk.end >= nearest) break;
            if (labels[i] != null && chunk.speakerId.equals(other.speakerId)) return labels[i];
        }
        return language;
    }

    String transcribe(RandomAccessFile audio, ChunkPlanner.Chunk chunk) throws IOException {
        OfflineStream stream = recognizer.createStream();
        try {
            stream.acceptWaveform(AudioDecoder.readPcm(audio, chunk.start, chunk.end), RATE);
            recognizer.decode(stream);
            return recognizer.getResult(stream).getText().trim();
        } finally {
            stream.release();
        }
    }

    private static String requireAsset(AssetManager assets, String path) throws IOException {
        assets.open(path).close();
        return path;
    }

    @Override public void close() {
        recognizer.release();
    }
}
