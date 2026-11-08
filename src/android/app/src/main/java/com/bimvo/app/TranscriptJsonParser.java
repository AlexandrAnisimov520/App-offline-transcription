package com.bimvo.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;
import java.util.ArrayList;
import java.util.List;

/** Strict external and local-engine JSON adapter for the organizer's schema. */
public final class TranscriptJsonParser {
    private TranscriptJsonParser() {}
    public static List<Utterance> parse(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        JSONArray transcript = root.getJSONArray("transcript");
        List<Utterance> turns = new ArrayList<>();
        for (int i=0; i<transcript.length(); i++) {
            JSONObject obj=transcript.getJSONObject(i);
            Object start=obj.get("start_ms"), end=obj.get("end_ms");
            if (!(start instanceof Number) || !(end instanceof Number) ||
                    ((Number)start).doubleValue()!=((Number)start).longValue() ||
                    ((Number)end).doubleValue()!=((Number)end).longValue())
                throw new JSONException("start_ms and end_ms must be integers");
            turns.add(new Utterance(i, obj.getString("speaker_id"),
                    ((Number)start).longValue(),((Number)end).longValue(),obj.getString("text")));
        }
        if (turns.isEmpty()) throw new JSONException("Transcript is empty");
        return turns;
    }
}
