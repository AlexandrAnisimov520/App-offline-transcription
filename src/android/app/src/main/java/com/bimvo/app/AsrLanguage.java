package com.bimvo.app;

import java.util.ArrayList;
import java.util.List;

/** Explicit routing applies to new jobs; persisted chunk languages remain stable on resume. */
enum AsrLanguage {
    AUTO("Автоматически"), RU("Русский"), EN("English");
    final String title;
    AsrLanguage(String title) { this.title = title; }
    static AsrLanguage parse(String value) {
        for (AsrLanguage mode : values()) if (mode.name().equals(value)) return mode;
        return AUTO;
    }
    List<ChunkPlanner.Chunk> force(List<ChunkPlanner.Chunk> chunks, String ru, String en) {
        if (this == AUTO) throw new IllegalStateException("Automatic routing needs LID");
        List<ChunkPlanner.Chunk> result = new ArrayList<>();
        for (ChunkPlanner.Chunk chunk : chunks)
            result.add(new ChunkPlanner.Chunk(chunk.index, chunk.start, chunk.end,
                    this == EN ? en : ru, chunk.speakerId));
        return result;
    }
}
