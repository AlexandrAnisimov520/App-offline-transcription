package com.bimvo.app;

import android.content.Context;
import com.k2fsa.sherpa.onnx.*;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Frozen tested candidate; a single global clustering pass for the entire recording. */
final class NativeSpeakerDiarization {
    static List<SpeakerInterval> run(Context context, File pcm, BooleanSupplier cancelled,
            SpeakerDiarization.Progress progress) throws Exception {
        long count = pcm.length() / 2;
        if ((pcm.length() & 1) != 0 || count < 320 || count > Integer.MAX_VALUE - 8)
            throw new IOException("Некорректный размер PCM для диаризации");
        if (count > 600L * 16000) throw new IOException("Поддерживаются записи до 10 минут");
        Runtime heap = Runtime.getRuntime();
        long available = heap.maxMemory() - (heap.totalMemory() - heap.freeMemory());
        if (count * 4L + 64L * 1024 * 1024 > available)
            throw new IOException("Недостаточно оперативной памяти для диаризации всей записи");
        float[] samples = new float[(int) count];
        try (RandomAccessFile input = new RandomAccessFile(pcm, "r")) {
            byte[] buffer = new byte[65536];
            for (int offset = 0; offset < samples.length;) {
                SpeakerClustering.check(cancelled);
                int n = Math.min(buffer.length / 2, samples.length - offset);
                input.readFully(buffer, 0, n * 2);
                for (int i = 0; i < n; i++) samples[offset+i] =
                        (short)((buffer[2*i] & 255) | (buffer[2*i+1] << 8)) / 32768f;
                offset += n;
            }
        }
        OfflineSpeakerDiarizationConfig config = new OfflineSpeakerDiarizationConfig(
            new OfflineSpeakerSegmentationModelConfig(new OfflineSpeakerSegmentationPyannoteModelConfig(
                "models/diarization/segmentation.onnx", .1f), 2, false, "cpu"),
            new SpeakerEmbeddingExtractorConfig("models/diarization/campplus_zh_en.onnx", 2, false, "cpu"),
            new FastClusteringConfig(-1, .7f, false), .15f, .3f);
        OfflineSpeakerDiarization engine = new OfflineSpeakerDiarization(context.getAssets(), config);
        try {
            if (engine.sampleRate() != 16000) throw new IOException("Diarization sample rate mismatch");
            progress.update("speakers", 15);
            OfflineSpeakerDiarizationSegment[] segments = engine.process(samples);
            progress.update("speakers", 29);
            SpeakerClustering.check(cancelled);
            Arrays.sort(segments, Comparator.comparingDouble(OfflineSpeakerDiarizationSegment::getStart)
                    .thenComparingDouble(OfflineSpeakerDiarizationSegment::getEnd)
                    .thenComparingInt(OfflineSpeakerDiarizationSegment::getSpeaker));
            LinkedHashMap<Integer,String> ids = new LinkedHashMap<>();
            List<SpeakerInterval> result = new ArrayList<>();
            for (OfflineSpeakerDiarizationSegment s : segments) {
                if (!Float.isFinite(s.getStart()) || !Float.isFinite(s.getEnd()) || s.getSpeaker() < 0)
                    throw new IOException("Invalid native diarization result");
                long start = Math.max(0, Math.round((double)s.getStart() * 16000));
                long end = Math.min(count, Math.round((double)s.getEnd() * 16000));
                if (end <= start) continue;
                String id = ids.computeIfAbsent(s.getSpeaker(), key -> "speaker_" + (ids.size()+1));
                result.add(new SpeakerInterval(start, end, id));
            }
            if (result.isEmpty()) throw new IOException("В записи не обнаружена речь");
            return result;
        } finally { engine.release(); }
    }
}
