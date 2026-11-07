package com.bimvo.app;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Visual grouping only: retain speaker changes and silence, leave transcript/timestamps intact. */
final class SpeakerTimelineIntervals {
    static final class Span {
        final String speaker;
        final long start;
        long end;
        Span(Utterance turn) { speaker = turn.speakerId; start = turn.startMs; end = turn.endMs; }
    }
    static List<Span> group(List<Utterance> turns) {
        List<Utterance> sorted = new ArrayList<>(turns);
        sorted.sort(Comparator.comparingLong((Utterance u) -> u.startMs).thenComparingLong(u -> u.endMs));
        List<Span> result = new ArrayList<>();
        for (Utterance turn : sorted) {
            Span previous = result.isEmpty() ? null : result.get(result.size() - 1);
            if (previous != null && previous.speaker.equals(turn.speakerId) && turn.startMs <= previous.end)
                previous.end = Math.max(previous.end, turn.endMs);
            else result.add(new Span(turn));
        }
        return result;
    }
}
