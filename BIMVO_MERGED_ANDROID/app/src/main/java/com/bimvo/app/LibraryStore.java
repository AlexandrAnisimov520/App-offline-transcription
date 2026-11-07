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
        super(context, "bimvo-library.db", null, 3);
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
        createProcessingTables(db);
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
        if (oldVersion < 2) createProcessingTables(db);
        else if (oldVersion < 3) db.execSQL("ALTER TABLE audio_chunks ADD COLUMN speaker_id TEXT NOT NULL DEFAULT 'speaker_unknown'");
        if (newVersion > 3) throw new IllegalStateException("Missing library migration to " + newVersion);
    }

    private void createProcessingTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE processing_jobs (recording_id TEXT PRIMARY KEY "
                + "REFERENCES recordings(id) ON DELETE CASCADE, status TEXT NOT NULL DEFAULT 'queued', "
                + "stage TEXT NOT NULL DEFAULT 'waiting', percent INTEGER NOT NULL DEFAULT 0, "
                + "completed_parts INTEGER NOT NULL DEFAULT 0, total_parts INTEGER NOT NULL DEFAULT 0, "
                + "error TEXT NOT NULL DEFAULT '', model_version TEXT NOT NULL DEFAULT 'GigaAM-v3-e2e-ctc-int8', "
                + "updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE audio_chunks (recording_id TEXT NOT NULL REFERENCES recordings(id) "
                + "ON DELETE CASCADE, chunk_index INTEGER NOT NULL, start_sample INTEGER NOT NULL, "
                + "end_sample INTEGER NOT NULL, cut_reason TEXT NOT NULL, text TEXT NOT NULL DEFAULT '', "
                + "finished INTEGER NOT NULL DEFAULT 0, speaker_id TEXT NOT NULL DEFAULT 'speaker_unknown', PRIMARY KEY(recording_id,chunk_index))");
    }

    static final class Job {
        final String id, status, stage, error;
        final int percent, completed, total;
        Job(String id, String status, String stage, int percent, int completed, int total, String error) {
            this.id = id; this.status = status; this.stage = stage; this.percent = percent;
            this.completed = completed; this.total = total; this.error = error;
        }
        boolean active() { return "queued".equals(status) || "running".equals(status); }
        boolean canResume() { return "paused".equals(status) || "failed".equals(status) || "interrupted".equals(status); }
    }

    java.util.Map<String, Job> loadJobs() {
        java.util.Map<String, Job> jobs = new java.util.HashMap<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT recording_id,status,stage,percent,"
                + "completed_parts,total_parts,error FROM processing_jobs", null)) {
            while (c.moveToNext()) {
                Job j = new Job(c.getString(0), c.getString(1), c.getString(2), c.getInt(3),
                        c.getInt(4), c.getInt(5), c.getString(6));
                jobs.put(j.id, j);
            }
        }
        return jobs;
    }

    Job job(String id) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT recording_id,status,stage,percent,"
                + "completed_parts,total_parts,error FROM processing_jobs WHERE recording_id=?", new String[]{id})) {
            return c.moveToFirst() ? new Job(c.getString(0),c.getString(1),c.getString(2),
                    c.getInt(3),c.getInt(4),c.getInt(5),c.getString(6)) : null;
        }
    }

    java.util.List<String> recoverJobs() {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues pause = new ContentValues();
        pause.put("status", "interrupted"); pause.put("stage", "interrupted");
        db.update("processing_jobs",pause,"status IN ('running','queued')",null);
        List<String> resume = new ArrayList<>();
        try (Cursor c = db.rawQuery("SELECT recording_id FROM processing_jobs WHERE status='interrupted' ORDER BY updated_at", null)) {
            while (c.moveToNext()) resume.add(c.getString(0));
        }
        return resume;
    }

    boolean queueJob(String id) {
        SQLiteDatabase db = getWritableDatabase();
        Job existing = job(id);
        if (existing != null && (existing.active() || "complete".equals(existing.status))) return false;
        ContentValues values = new ContentValues();
        values.put("status", "queued"); values.put("stage", "waiting"); values.put("error", "");
        values.put("updated_at", System.currentTimeMillis());
        if (existing == null) {
            values.put("recording_id",id);
            return db.insert("processing_jobs", null, values) != -1;
        }
        return db.update("processing_jobs", values, "recording_id=?", new String[]{id}) == 1;
    }

    void updateJob(String id, String status, String stage, int percent, String error) {
        ContentValues values = new ContentValues();
        values.put("status", status); values.put("stage", stage);
        values.put("percent", Math.max(0, Math.min(100, percent)));
        values.put("error", error == null ? "" : (error.length() > 240 ? error.substring(0,240) : error));
        values.put("updated_at",System.currentTimeMillis());
        getWritableDatabase().update("processing_jobs", values, "recording_id=?", new String[]{id});
    }

    void setChunkPlan(String id, List<ChunkPlanner.Chunk> parts) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("audio_chunks", "recording_id=?", new String[]{id});
            for (ChunkPlanner.Chunk part : parts) {
                ContentValues value = new ContentValues();
                value.put("recording_id", id); value.put("chunk_index", part.index);
                value.put("start_sample",part.start); value.put("end_sample",part.end);
                value.put("cut_reason",part.reason); value.put("speaker_id",part.speakerId);
                db.insertOrThrow("audio_chunks",null,value);
            }
            ContentValues job = new ContentValues();
            job.put("total_parts",parts.size()); job.put("completed_parts",0);
            job.put("percent",60); job.put("stage","asr");
            job.put("model_version",BilingualAsr.MODEL_VERSION); job.put("updated_at",System.currentTimeMillis());
            db.update("processing_jobs",job,"recording_id=?",new String[]{id});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    List<ChunkPlanner.Chunk> chunks(String id) {
        List<ChunkPlanner.Chunk> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT chunk_index,start_sample,end_sample,cut_reason,speaker_id "
                + "FROM audio_chunks WHERE recording_id=? ORDER BY chunk_index", new String[]{id})) {
            while (c.moveToNext()) result.add(new ChunkPlanner.Chunk(c.getInt(0),c.getLong(1),c.getLong(2),c.getString(3),c.getString(4)));
        }
        return result;
    }

    java.util.Set<Integer> completedChunks(String id) {
        java.util.Set<Integer> done = new java.util.HashSet<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT chunk_index FROM audio_chunks "
                + "WHERE recording_id=? AND finished=1",new String[]{id})) {
            while (c.moveToNext()) done.add(c.getInt(0));
        }
        return done;
    }

    void finishChunk(String id, ChunkPlanner.Chunk part, String text) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues chunk = new ContentValues();
            chunk.put("text",text); chunk.put("finished",1);
            if (db.update("audio_chunks",chunk,"recording_id=? AND chunk_index=? AND finished=0",
                    new String[]{id,String.valueOf(part.index)}) != 1)
                throw new IllegalStateException("Chunk is missing or already saved");
            int finished = 0, total = 0;
            try (Cursor c = db.rawQuery("SELECT COUNT(*),SUM(finished) FROM audio_chunks "
                    + "WHERE recording_id=?",new String[]{id})) {
                if (c.moveToFirst()) { total = c.getInt(0); finished = c.getInt(1); }
            }
            ContentValues progress = new ContentValues();
            progress.put("completed_parts",finished); progress.put("total_parts",total);
            progress.put("percent",total == 0 ? 60 : 60 + 39 * finished / total);
            progress.put("updated_at",System.currentTimeMillis());
            db.update("processing_jobs",progress,"recording_id=?",new String[]{id});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    void completeAudioJob(String id) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            List<Utterance> turns = new ArrayList<>();
            boolean recognizedWords = false;
            try (Cursor c = db.rawQuery("SELECT chunk_index,start_sample,end_sample,text,speaker_id,finished FROM audio_chunks WHERE recording_id=? ORDER BY chunk_index", new String[]{id})) {
                while (c.moveToNext()) {
                    if (c.getInt(5) != 1) throw new IllegalStateException("Incomplete audio job");
                    if (!c.getString(3).trim().isEmpty()) recognizedWords = true;
                    turns.add(new Utterance(c.getInt(0), c.getString(4),
                            c.getLong(1) * 1000L / 16000, Math.max(c.getLong(1) * 1000L / 16000 + 1, c.getLong(2) * 1000L / 16000),
                            c.getString(3).trim().isEmpty() ? Utterance.UNRECOGNIZED_TEXT : c.getString(3)));
                }
            }
            if (!recognizedWords) throw new IllegalStateException("Не удалось распознать слова в записи");
            db.delete("utterances", "recording_id=?", new String[]{id});
            for (Utterance turn : turns) db.insertOrThrow("utterances", null, utteranceValues(id, turn));
            updateJob(id, "complete", "done", 100, "");
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    void resetAudioJob(String id) {
        Job existing = job(id);
        if (existing != null && existing.active()) throw new IllegalStateException("Обработка уже запущена");
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("audio_chunks", "recording_id=?", new String[]{id});
            db.delete("processing_jobs", "recording_id=?", new String[]{id});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        // Keep the previous transcript until the entire new job commits successfully.
    }

    File processingFile(String id) {
        if (!id.matches("[a-f0-9-]{36}")) throw new IllegalArgumentException("Invalid recording id");
        return new File(new File(context.getFilesDir(),"processing"),id+".pcm");
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
        String original;
        try (Cursor cursor = getReadableDatabase().query("recordings", new String[]{"original_name"},
                "id=?", new String[]{id}, null, null, null)) {
            if (!cursor.moveToFirst()) throw new IllegalStateException("Recording not found");
            original = cursor.getString(0);
        }
        String extension = "";
        int dot = original == null ? -1 : original.lastIndexOf('.');
        if (dot >= 0 && original.substring(dot).matches("\\.[A-Za-z0-9]{1,10}")) extension = original.substring(dot);
        String filename = ImportFiles.safeDisplayName(clean).replace('/', '_').replace('\\', '_');
        if (!extension.isEmpty() && !filename.toLowerCase(java.util.Locale.ROOT).endsWith(extension.toLowerCase(java.util.Locale.ROOT)))
            filename += extension;
        values.put("original_name", filename);
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
        processingFile(recording.id).delete();
        android.content.SharedPreferences summaries = context.getSharedPreferences("qwen-summaries-v1", 0);
        android.content.SharedPreferences.Editor edit = summaries.edit().remove("summary:" + recording.id);
        for (String key : summaries.getAll().keySet()) {
            if (key.startsWith(recording.id + ":") || key.contains(":" + recording.id + ":")) edit.remove(key);
        }
        edit.commit();
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
        File processing = new File(context.getFilesDir(),"processing");
        if (!processing.isDirectory() && !processing.mkdirs()) throw new IOException("Processing directory unavailable");
        File[] temporary = processing.listFiles();
        if (temporary != null) for (File f : temporary) {
            String name = f.getName();
            if (name.endsWith(".part") || !name.matches("[a-f0-9-]{36}\\.pcm")
                    || !referenced.contains(findStoredNameForId(records, name.substring(0,Math.min(36,name.length())))))
                f.delete();
        }
    }

    private static String findStoredNameForId(List<Recording> records, String id) {
        for (Recording record : records) if (record.id.equals(id)) return record.storedName;
        return "";
    }
}
