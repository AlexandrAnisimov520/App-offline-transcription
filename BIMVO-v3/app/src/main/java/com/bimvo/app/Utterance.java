package com.bimvo.app;

import java.util.Objects;

/** One real or explicitly labelled demo turn. All times are milliseconds. */
public final class Utterance {
    public final int id;
    public final String speakerId;
    public final long startMs, endMs;
    public final String text;

    public Utterance(int id, String speakerId, long startMs, long endMs, String text) {
        if (id < 0 || speakerId == null || speakerId.trim().isEmpty()
                || startMs < 0 || endMs <= startMs || text == null || text.trim().isEmpty())
            throw new IllegalArgumentException("Invalid utterance");
        this.id = id;
        this.speakerId = speakerId.trim();
        this.startMs = startMs;
        this.endMs = endMs;
        this.text = text.trim();
    }

    public Utterance withText(String value) {
        return new Utterance(id, speakerId, startMs, endMs, value);
    }

    public Utterance withTiming(String speaker, long start, long end) {
        return new Utterance(id, speaker, start, end, text);
    }

    @Override public boolean equals(Object other) {
        if (!(other instanceof Utterance)) return false;
        Utterance u = (Utterance) other;
        return id == u.id && startMs == u.startMs && endMs == u.endMs
                && speakerId.equals(u.speakerId) && text.equals(u.text);
    }

    @Override public int hashCode() { return Objects.hash(id, speakerId, startMs, endMs, text); }
}
