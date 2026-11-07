package com.bimvo.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Pure arithmetic: intervals cover every sample once, without gaps or overlaps. */
final class ChunkPlanner {
    static final int SAMPLE_RATE = 16_000;
    static final long TARGET = 18L * SAMPLE_RATE;
    static final long LIMIT = 235L * SAMPLE_RATE / 10L;
    static final long MIN_CHUNK = 320L; // enough samples for one ASR spectrogram window

    static final class Chunk {
        final int index;
        final long start, end;
        final String reason, speakerId;
        Chunk(int index, long start, long end, String reason) {
            this(index, start, end, reason, "speaker_unknown");
        }
        Chunk(int index, long start, long end, String reason, String speakerId) {
            this.index = index; this.start = start; this.end = end; this.reason = reason; this.speakerId = speakerId;
        }
    }

    static List<Chunk> plan(long samples, List<Long> detectedPauses) {
        if (samples <= 0) throw new IllegalArgumentException("Empty audio");
        List<Long> pauses = new ArrayList<>(detectedPauses);
        Collections.sort(pauses);
        List<Chunk> result = new ArrayList<>();
        long start = 0;
        while (start < samples) {
            long remaining = samples - start;
            if (remaining <= LIMIT) {
                result.add(new Chunk(result.size(), start, samples, "end"));
                break;
            }
            long target = start + TARGET;
            // Never leave a one-sample (unrecognizable) last chunk.
            long max = Math.min(start + LIMIT, samples - MIN_CHUNK);
            long best = -1;
            long bestDistance = Long.MAX_VALUE;
            for (long cut : pauses) {
                if (cut <= start + 6L * SAMPLE_RATE) continue;
                if (cut > max) break;
                long distance = Math.abs(target - cut);
                if (distance < bestDistance) { bestDistance = distance; best = cut; }
            }
            boolean onPause = best >= 0;
            long cut = onPause ? best : Math.min(target, max);
            if (cut <= start || cut - start > LIMIT) throw new IllegalStateException("Invalid chunk boundary");
            result.add(new Chunk(result.size(), start, cut, onPause ? "silence" : "forced"));
            start = cut;
        }
        long cursor = 0;
        for (Chunk chunk : result) {
            if (chunk.start != cursor || chunk.end <= cursor || chunk.end - cursor > LIMIT)
                throw new IllegalStateException("Non-contiguous chunks");
            cursor = chunk.end;
        }
        if (cursor != samples) throw new IllegalStateException("Audio data lost");
        return result;
    }
}
