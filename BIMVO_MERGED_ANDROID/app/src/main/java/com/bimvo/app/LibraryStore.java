package com.bimvo.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** All calls run on BimvoApplication's single IO executor, never on the UI thread. */
final class LibraryStore extends SQLiteOpenHelper {
    private final Context context;
    final File audioDirectory;

    LibraryStore(Context context) {
        super(context, "bimvo-library.db", null, 1);
        this.context = context.getApplicationContext();
        audioDirectory = new File(context.getFilesDir(), "audio");
        setWriteAheadLoggingEnabled(true);
    }

    @Override public void onConfigure(SQLiteDatabase db) { db.setForeignKeyConstraintsEnabled(true); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE recordings (id TEXT PRIMARY KEY, title TEXT NOT NULL, "
                + "original_name TEXT NOT NULL, stored_name TEXT NOT NULL, mime_type TEXT NOT NULL, "
                + "sha256 TEXT UNIQUE, size_bytes INTEGER NOT NULL, duration_ms INTEGER NOT NULL, "
                + "created_at INTEGER NOT NULL, is_demo INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE utterances (recording_id TEXT NOT NULL REFERENCES recordings(id) "
                + "ON DELETE CASCADE, turn_id INTEGER NOT NULL, speaker_id TEXT NOT NULL, "
                + "start_ms INTEGER NOT NULL CHECK(start_ms >= 0), "
                + "end_ms INTEGER NOT NULL CHECK(end_ms > start_ms), text TEXT NOT NULL, "
                + "PRIMARY KEY(recording_id, turn_id))");
        db.execSQL("CREATE INDEX recordings_created ON recordings(created_at DESC)");
        seed(db, "demo-team", R.string.demo_team, 12, 1,
                new int[]{R.string.demo_team_1, R.string.demo_team_2, R.string.demo_team_3},
                new int[]{1, 2, 3}, new int[]{0, 12, 34, 60});
        seed(db, "demo-interview", R.string.demo_interview, 28, 2,
                new int[]{R.string.demo_interview_1, R.string.demo_interview_2, R.string.demo_interview_3},
                new int[]{1, 2, 1}, new int[]{0, 14, 42, 60});
        seed(db, "demo-project", R.string.demo_project, 41, 3,
                new int[]{R.string.demo_project_1, R.string.demo_project_2,
                        R.string.demo_project_3, R.string.demo_project_4},
                new int[]{1, 2, 3, 4}, new int[]{0, 15, 30, 45, 60});
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Future schema changes must migrate existing user data, never drop/recreate it.
        throw new IllegalStateException("Missing library migration " + oldVersion + " -> " + newVersion);
    }

    private void seed(SQLiteDatabase db, String id, int titleId, int minutes, int ageDays,
                      int[] texts, int[] speakers, int[] seconds) {
        String title = context.getString(titleId);
        Recording r = new Recording(id, title, title + ".mp3", "", "audio/mpeg", null,
                0, minutes * 60_000L, System.currentTimeMillis() - ageDays * 86_400_000L,
                true, Collections.emptyList());
        insertRecording(db, r);
        for (int i = 0; i < texts.length; i++) {
            Utterance u = new Utterance(i, "speaker_" + speakers[i], seconds[i] * 1_000L,
                    seconds[i + 1] * 1_000L, context.getString(texts[i]));
            db.insertOrThrow("utterances", null, utteranceValues(id, u));
        }
    }

    private static ContentValues recordingValues(Recording r) {
        ContentValues v = new ContentValues();
        v.put("id", r.id); v.put("title", r.title); v.put("original_name", r.originalName);
        v.put("stored_name", r.storedName); v.put("mime_type", r.mimeType); v.put("sha256", r.sha256);
        v.put("size_bytes", r.sizeBytes); v.put("duration_ms", r.durationMs);
        v.put("created_at", r.createdAt); v.put("is_demo", r.demo ? 1 : 0);
        return v;
    }

    private static ContentValues utteranceValues(String recordingId, Utterance u) {
        ContentValues v = new ContentValues();
        v.put("recording_id", recordingId); v.put("turn_id", u.id); v.put("speaker_id", u.speakerId);
        v.put("start_ms", u.startMs); v.put("end_ms", u.endMs); v.put("text", u.text);
        return v;
    }

    private void insertRecording(SQLiteDatabase db, Recording r) {
        db.insertOrThrow("recordings", null, recordingValues(r));
    }

    void insert(Recording r) { insertRecording(getWritableDatabase(), r); }

    String findHash(String sha256) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id FROM recordings WHERE sha256=?", new String[]{sha256})) {
            return c.moveToFirst() ? c.getString(0) : null;
        }
    }

    List<Recording> load() {
        List<Recording> result = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery("SELECT id,title,original_name,stored_name,mime_type,sha256,"
                + "size_bytes,duration_ms,created_at,is_demo FROM recordings ORDER BY created_at DESC,id", null)) {
            while (c.moveToNext()) {
                String id = c.getString(0);
                List<Utterance> turns = new ArrayList<>();
                try (Cursor t = db.rawQuery("SELECT turn_id,speaker_id,start_ms,end_ms,text FROM utterances "
                        + "WHERE recording_id=? ORDER BY start_ms,turn_id", new String[]{id})) {
                    while (t.moveToNext()) turns.add(new Utterance(t.getInt(0), t.getString(1),
                            t.getLong(2), t.getLong(3), t.getString(4)));
                }
                result.add(new Recording(id, c.getString(1), c.getString(2), c.getString(3),
                        c.getString(4), c.getString(5), c.getLong(6), c.getLong(7), c.getLong(8),
                        c.getInt(9) == 1, turns));
            }
        }
        return result;
    }

    void rename(String id, String title) {
        String clean = title.trim();
        if (clean.isEmpty() || clean.length() > 200) throw new IllegalArgumentException("Invalid title");
        ContentValues values = new ContentValues();
        values.put("title", clean);
        if (getWritableDatabase().update("recordings", values, "id=?", new String[]{id}) != 1)
            throw new IllegalStateException("Recording not found");
    }

    void updateUtterance(String recordingId, Utterance u) {
        if (getWritableDatabase().update("utterances", utteranceValues(recordingId, u),
                "recording_id=? AND turn_id=?", new String[]{recordingId, String.valueOf(u.id)}) != 1)
            throw new IllegalStateException("Utterance not found");
    }

    void replaceTranscript(String recordingId, List<Utterance> turns) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("utterances", "recording_id=?", new String[]{recordingId});
            for (Utterance turn : turns) db.insertOrThrow("utterances",null,utteranceValues(recordingId,turn));
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    File audioFile(Recording recording) {
        if (recording.demo || !recording.storedName.matches("[a-f0-9-]{36}\\.[a-z0-9]{1,8}"))
            throw new IllegalArgumentException("Not a stored audio file");
        return new File(audioDirectory, recording.storedName);
    }

    void delete(Recording recording) throws IOException {
        if (recording.demo) throw new IllegalArgumentException("Demo records cannot be deleted");
        File original = audioFile(recording);
        File trash = new File(audioDirectory, ".trash-" + recording.storedName);
        boolean moved = original.exists();
        if (moved && !original.renameTo(trash)) throw new IOException("Cannot stage deletion");
        try {
            getWritableDatabase().delete("recordings", "id=?", new String[]{recording.id});
        } catch (RuntimeException e) {
            if (moved) trash.renameTo(original);
            throw e;
        }
        // If deletion fails, startup cleanup retries; the entry is already durably removed.
        if (moved) trash.delete();
    }

    void recoverFiles() throws IOException {
        if (!audioDirectory.isDirectory() && !audioDirectory.mkdirs()) throw new IOException("Audio directory unavailable");
        List<Recording> records = load();
        java.util.Set<String> referenced = new java.util.HashSet<>();
        for (Recording r : records) if (!r.demo) referenced.add(r.storedName);
        File[] files = audioDirectory.listFiles();
        if (files == null) return;
        for (File file : files) {
            String name = file.getName();
            if (name.startsWith(".trash-")) {
                String original = name.substring(7);
                File restore = new File(audioDirectory, original);
                if (referenced.contains(original) && !restore.exists()) file.renameTo(restore);
                else file.delete();
            } else if (name.endsWith(".part") || !referenced.contains(name)) {
                // Only our dedicated audio directory is scanned; external originals are never touched.
                file.delete();
            }
        }
    }
}
