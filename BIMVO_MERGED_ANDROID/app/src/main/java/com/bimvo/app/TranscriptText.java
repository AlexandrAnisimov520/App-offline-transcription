package com.bimvo.app;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Platform-independent formatter; JSON contract matches the case specification. */
public final class TranscriptText {
    private TranscriptText() { }

    public static String clock(long milliseconds) {
        long seconds = Math.max(0, milliseconds) / 1_000;
        return seconds >= 3_600
                ? String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3_600, seconds / 60 % 60, seconds % 60)
                : String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
    }

    public static String preciseClock(long ms) {
        return clock(ms) + (ms % 1_000 == 0 ? "" : String.format(Locale.ROOT, ".%03d", ms % 1_000));
    }

    private static String subtitleTime(long ms) {
        return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d",
                ms / 3_600_000, ms / 60_000 % 60, ms / 1_000 % 60, ms % 1_000);
    }

    public static String format(List<Utterance> input, String format) {
        if (!format.equals("JSON") && !format.equals("TXT") && !format.equals("SRT"))
            throw new IllegalArgumentException("Unsupported export format");
        if (input.isEmpty()) throw new IllegalArgumentException("No transcript to export");
        List<Utterance> turns = new ArrayList<>(input);
        turns.sort(Comparator.comparingLong((Utterance u) -> u.startMs).thenComparingInt(u -> u.id));
        StringBuilder result = new StringBuilder();
        if (format.equals("JSON")) result.append("{\n  \"transcript\": [\n");
        int number = 0;
        for (Utterance u : turns) {
            if (format.equals("JSON")) {
                if (number > 0) result.append(",\n");
                result.append("    {\"speaker_id\": ").append(quote(u.speakerId))
                        .append(", \"start_ms\": ").append(u.startMs)
                        .append(", \"end_ms\": ").append(u.endMs)
                        .append(", \"text\": ").append(quote(u.text)).append('}');
            } else if (format.equals("SRT")) {
                result.append(number + 1).append('\n').append(subtitleTime(u.startMs))
                        .append(" --> ").append(subtitleTime(u.endMs)).append('\n')
                        .append(u.speakerId).append(": ").append(u.text.replaceAll("\\R+", " "))
                        .append("\n\n");
            } else {
                result.append('[').append(preciseClock(u.startMs)).append(" - ")
                        .append(preciseClock(u.endMs)).append("] ").append(u.speakerId)
                        .append('\n').append(u.text).append("\n\n");
            }
            number++;
        }
        if (format.equals("JSON")) result.append("\n  ]\n}\n");
        return result.toString();
    }

    /** JSON escaping kept here so the exact export can be tested on a plain JDK. */
    private static String quote(String input) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 32 || Character.isSurrogate(c))
                        out.append(String.format(Locale.ROOT, "\\u%04x", (int)c));
                    else out.append(c);
            }
        }
        return out.append('"').toString();
    }
}
