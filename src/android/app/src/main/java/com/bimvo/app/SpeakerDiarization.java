package com.bimvo.app;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/** VAD -> overlapping analysis windows -> global labels -> disjoint speaker-aware ASR chunks. */
final class SpeakerDiarization {
    interface Progress { void update(String stage, int percent); }
    static final String VERSION = "diarization-campp-v1";

    static List<SpeakerClustering.Window> windows(List<long[]> speech) {
        List<SpeakerClustering.Window> result = new ArrayList<>();
        for (long[] span : speech) {
            List<SpeakerClustering.Window> local = new ArrayList<>();
            long width = Math.min(32000, span[1] - span[0]);
            if (width < 320) continue;
            for (long start = span[0];;) {
                local.add(new SpeakerClustering.Window(start, start + width));
                if (start + width == span[1]) break;
                start = Math.min(span[1] - width, start + 16000);
            }
            for (int i = 0; i < local.size(); i++) {
                SpeakerClustering.Window current = local.get(i);
                current.left = i == 0 ? span[0] : midpoint(local.get(i - 1), current);
                current.right = i == local.size() - 1 ? span[1] : midpoint(current, local.get(i + 1));
            }
            result.addAll(local);
        }
        return result;
    }
    private static long midpoint(SpeakerClustering.Window a, SpeakerClustering.Window b) {
        return (a.start + a.end + b.start + b.end) / 4;
    }
    static float[] read(RandomAccessFile file, long start, long end) throws IOException {
        if (start < 0 || end <= start || end - start > 64000) throw new IOException("Invalid speaker audio window");
        byte[] bytes = new byte[(int)(end - start) * 2];
        file.seek(start * 2); file.readFully(bytes);
        float[] wave = new float[bytes.length / 2];
        for (int i = 0; i < wave.length; i++) wave[i] = (short)((bytes[2 * i] & 255) | (bytes[2 * i + 1] << 8)) / 32768f;
        return wave;
    }
    static List<ChunkPlanner.Chunk> plan(Context context, File pcm, BooleanSupplier cancelled, Progress progress) throws Exception {
        return DiarizationBridge.plan(context, pcm, cancelled, progress);
    }

    static List<ChunkPlanner.Chunk> chunks(List<SpeakerClustering.Window> windows) {
        List<ChunkPlanner.Chunk> result = new ArrayList<>();
        long start = -1, end = -1; int label = -1;
        for (SpeakerClustering.Window w : windows) {
            if (w.right <= w.left) continue;
            if (start >= 0 && (w.label != label || w.left - end > 4000 || w.right - start > ChunkPlanner.LIMIT)) {
                append(result, start, end, label); start = -1;
            }
            if (start < 0) { start = w.left; label = w.label; }
            end = w.right;
        }
        if (start >= 0) append(result, start, end, label);
        return result;
    }
    private static void append(List<ChunkPlanner.Chunk> result, long start, long end, int label) {
        if (end - start < ChunkPlanner.MIN_CHUNK) return;
        result.add(new ChunkPlanner.Chunk(result.size(), start, end, VERSION,
                label == 0 ? "speaker_unknown" : "speaker_" + label));
    }
}
