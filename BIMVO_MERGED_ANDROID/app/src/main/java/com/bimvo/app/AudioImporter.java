package com.bimvo.app;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.FileInputStream;
import java.io.InterruptedIOException;
import java.util.Collections;
import java.util.Locale;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** SAF URI -> private file -> metadata. No decoding of full audio, no inference, no network. */
final class AudioImporter {
    interface Progress { void update(String name, long copied, long total); }
    static final class Failure extends IOException {
        final int messageRes;
        Failure(int messageRes) { this.messageRes = messageRes; }
    }
    static final class Result {
        final String id;
        final boolean duplicate;
        Result(String id, boolean duplicate) { this.id = id; this.duplicate = duplicate; }
    }

    static Result importAudio(Context context, LibraryStore store, Uri uri,
                              BooleanSupplier cancelled, Progress progress) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        String name = "audio", mime = resolver.getType(uri);
        long declaredSize = -1;
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (nameColumn >= 0 && !cursor.isNull(nameColumn)) name = cursor.getString(nameColumn);
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) declaredSize = cursor.getLong(sizeColumn);
            }
        } catch (RuntimeException ignored) { /* Metadata is optional; the stream is authoritative. */ }
        name = ImportFiles.safeDisplayName(name);
        String extension = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        boolean knownAudio = extension.matches("mp3|wav|m4a|aac|ogg|opus|flac|amr|3gp|wma|aiff|aif|mp4|caf|mka");
        if ((mime == null || !mime.startsWith("audio/")) && !knownAudio && !"application/ogg".equals(mime))
            throw new Failure(R.string.import_not_audio);
        if (!knownAudio) extension = "audio";
        if (declaredSize > 0 && declaredSize > Math.max(0, store.audioDirectory.getUsableSpace() - ImportFiles.FREE_SPACE_RESERVE))
            throw new ImportFiles.NoSpaceException();
        final String displayName = name;
        final long total = declaredSize;
        progress.update(displayName, 0, total);
        String id = UUID.randomUUID().toString();
        File partial = new File(store.audioDirectory, id + ".part");
        File destination = new File(store.audioDirectory, id + "." + extension);
        boolean committed = false;
        try {
            ImportFiles.CopyResult copy;
            try (InputStream input = resolver.openInputStream(uri)) {
                if (input == null) throw new Failure(R.string.import_unreadable);
                long[] lastReport = {0};
                copy = ImportFiles.copy(input, partial, cancelled, copied -> {
                    long now = android.os.SystemClock.elapsedRealtime();
                    if (now - lastReport[0] >= 200) {
                        progress.update(displayName, copied, total);
                        lastReport[0] = now;
                    }
                });
            }
            String existing = store.findHash(copy.sha256);
            if (existing != null) return new Result(existing, true);
            if (cancelled.getAsBoolean()) throw new InterruptedIOException("Import cancelled");
            if (!partial.renameTo(destination)) throw new IOException("Cannot finalize audio file");
            long duration = readDuration(destination);
            if (cancelled.getAsBoolean()) throw new InterruptedIOException("Import cancelled");
            String title = displayName.lastIndexOf('.') > 0
                    ? displayName.substring(0, displayName.lastIndexOf('.')) : displayName;
            Recording record = new Recording(id, title, displayName, destination.getName(),
                    mime == null ? "application/octet-stream" : mime, copy.sha256, copy.bytes, duration,
                    System.currentTimeMillis(), false, Collections.emptyList());
            store.insert(record);
            committed = true;
            progress.update(displayName, copy.bytes, copy.bytes);
            return new Result(id, false);
        } finally {
            partial.delete();
            if (!committed) destination.delete();
        }
    }

    /** Capture -> private library; keeps one canonical store for imported and recorded audio. */
    static Result importCaptured(Context context, LibraryStore store, File source) throws IOException {
        if (!source.isFile() || source.length() == 0) throw new ImportFiles.EmptyFileException();
        if (source.length() > Math.max(0, store.audioDirectory.getUsableSpace() - ImportFiles.FREE_SPACE_RESERVE))
            throw new ImportFiles.NoSpaceException();
        String id = UUID.randomUUID().toString();
        File partial = new File(store.audioDirectory, id + ".part");
        File destination = new File(store.audioDirectory, id + ".m4a");
        boolean committed = false;
        try {
            ImportFiles.CopyResult copied;
            try (InputStream input = new FileInputStream(source)) {
                copied = ImportFiles.copy(input, partial, () -> false, bytes -> {});
            }
            String existing = store.findHash(copied.sha256);
            if (existing != null) return new Result(existing, true);
            if (!partial.renameTo(destination)) throw new IOException("Cannot finalize microphone capture");
            String title = "Запись " + new java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
                    .format(new java.util.Date());
            long duration = readDuration(destination);
            Recording record = new Recording(id, title, title + ".m4a", destination.getName(),
                    "audio/mp4", copied.sha256, copied.bytes, duration,
                    System.currentTimeMillis(), false, Collections.emptyList());
            store.insert(record);
            committed = true;
            return new Result(id, false);
        } finally {
            partial.delete();
            if (!committed) destination.delete();
        }
    }

    private static long readDuration(File file) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            String value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return value == null ? -1 : Math.max(-1, Long.parseLong(value));
        } catch (RuntimeException e) {
            // Some codecs/providers expose no duration. Still preserve the selected audio bytes.
            return -1;
        } finally {
            try { retriever.release(); } catch (Exception ignored) { }
        }
    }
}
