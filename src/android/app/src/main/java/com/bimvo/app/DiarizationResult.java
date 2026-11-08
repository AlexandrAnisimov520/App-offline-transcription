package com.bimvo.app;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;

/** Keep ASR context/boundaries intact; never duplicate a mixed waveform as two transcripts. */
final class DiarizationResult {
    final List<SpeakerInterval> intervals;
    final List<ChunkPlanner.Chunk> chunks, original;
    DiarizationResult(List<SpeakerInterval> intervals, List<ChunkPlanner.Chunk> original) {
        this.original = java.util.Collections.unmodifiableList(new ArrayList<>(original));
        this.intervals = java.util.Collections.unmodifiableList(new ArrayList<>(intervals));
        chunks = new ArrayList<>();
        for (ChunkPlanner.Chunk chunk : original) {
            LinkedHashSet<String> voices = new LinkedHashSet<>();
            for (SpeakerInterval s : intervals)
                if (s.start < chunk.end && s.end > chunk.start) voices.add(s.speakerId);
            String speaker = voices.size() == 1 ? voices.iterator().next() : "speaker_unknown";
            chunks.add(new ChunkPlanner.Chunk(chunk.index, chunk.start, chunk.end, chunk.reason, speaker));
        }
    }
}
