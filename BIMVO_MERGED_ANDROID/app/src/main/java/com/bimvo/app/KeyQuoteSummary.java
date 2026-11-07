package com.bimvo.app;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Extractive, explicitly non-LLM selection from actual ASR turns. */
public final class KeyQuoteSummary {
    private KeyQuoteSummary() {}
    public static String build(List<Utterance> turns, int limit) {
        if (turns == null || turns.isEmpty()) return "Нет распознанных реплик. Сначала подключите ASR.";
        List<Utterance> candidates = new ArrayList<>(turns);
        // Simple transparent heuristic; avoids hallucinating words and keeps exact source text.
        candidates.sort(Comparator.<Utterance>comparingDouble(KeyQuoteSummary::score).reversed());
        int count = Math.min(Math.max(1,limit), candidates.size());
        candidates = new ArrayList<>(candidates.subList(0,count));
        candidates.sort(Comparator.comparingLong(u -> u.startMs));
        StringBuilder out = new StringBuilder("Цитаты из полной расшифровки (без генерации текста):\n\n");
        for (Utterance u : candidates) out.append(TranscriptText.clock(u.startMs)).append(" · ")
                .append(u.speakerId).append(": ").append(u.text).append("\n\n");
        return out.toString();
    }
    private static double score(Utterance u) {
        String t = u.text.toLowerCase(Locale.ROOT);
        double score = Math.min(t.length(), 180) / 40.0;
        for (String k : new String[]{"важно", "решили", "нужно", "срок", "итог", "договор", "задач", "предлага", "план"})
            if (t.contains(k)) score += 2;
        return score;
    }
}
