package com.bimvo.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Immutable UI snapshot. An imported audio file has NO transcript until an engine produces it. */
public final class Recording {
    public final String id, title, originalName, storedName, mimeType, sha256;
    public final long sizeBytes, durationMs, createdAt;
    public final boolean demo;
    public final List<Utterance> transcript;

    public Recording(String id, String title, String originalName, String storedName,
                     String mimeType, String sha256, long sizeBytes, long durationMs,
                     long createdAt, boolean demo, List<Utterance> transcript) {
        this.id = id;
        this.title = title;
        this.originalName = originalName;
        this.storedName = storedName;
        this.mimeType = mimeType;
        this.sha256 = sha256;
        this.sizeBytes = sizeBytes;
        this.durationMs = durationMs;
        this.createdAt = createdAt;
        this.demo = demo;
        List<Utterance> ordered = new ArrayList<>(transcript);
        ordered.sort(Comparator.comparingLong((Utterance u) -> u.startMs).thenComparingInt(u -> u.id));
        this.transcript = Collections.unmodifiableList(ordered);
    }

    public Utterance utterance(int id) {
        for (Utterance u : transcript) if (u.id == id) return u;
        return null;
    }

    public List<String> speakers() {
        Set<String> ids = new LinkedHashSet<>();
        for (Utterance u : transcript) ids.add(u.speakerId);
        return new ArrayList<>(ids);
    }

    public long transcriptDurationMs() {
        long end = 0;
        for (Utterance u : transcript) end = Math.max(end, u.endMs);
        return Math.max(demo ? 60_000L : 1_000L, end);
    }

    public boolean matches(String query) {
        String needle = query.trim().toLowerCase(Locale.ROOT);
        if (title.toLowerCase(Locale.ROOT).contains(needle)
                || originalName.toLowerCase(Locale.ROOT).contains(needle)) return true;
        for (Utterance u : transcript)
            if (u.text.toLowerCase(Locale.ROOT).contains(needle)) return true;
        return false;
    }
}
