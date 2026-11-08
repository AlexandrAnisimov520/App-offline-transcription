package com.bimvo.app;

import android.app.Application;
import android.content.Intent;
import android.content.UriPermission;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import com.bimvo.app.ml.LocalTranscriber;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Process-scoped jobs survive Activity rotation without retaining a screen or a View. */
public final class BimvoApplication extends Application {
    interface Listener {
        void onLibraryChanged();
        void onImportProgress();
    }
    private enum Operation { NONE, IMPORT, WRITE, EXPORT }
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Set<Listener> listeners = new LinkedHashSet<>();
    private final AtomicBoolean cancelImport = new AtomicBoolean();
    private LibraryStore store;
    private List<Recording> recordings = Collections.emptyList();
    private Operation operation = Operation.NONE;
    boolean ready;
    String processingStatus = "";
    int loadError;
    long noticeSerial = System.currentTimeMillis();
    int notice;
    String lastImportedId = "";
    String importName = "";
    long importedBytes, expectedBytes = -1;
    private ExportDraft pendingExport;

    static final class ExportDraft {
        final String token, name, mime;
        ExportDraft(String token, String name, String mime) { this.token = token; this.name = name; this.mime = mime; }
    }

    @Override public void onCreate() {
        super.onCreate();
        store = new LibraryStore(this);
        io.execute(() -> {
            try {
                store.recoverFiles();
                // Only temporary import grants are used. Interrupted copies are cleaned on startup.
                for (UriPermission permission : getContentResolver().getPersistedUriPermissions()) {
                    try { getContentResolver().releasePersistableUriPermission(permission.getUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION); }
                    catch (SecurityException ignored) { }
                }
                cleanExportCache();
                List<Recording> data = store.load();
                main.post(() -> { recordings = Collections.unmodifiableList(data); ready = true; notifyChanged(); });
            } catch (Exception e) {
                main.post(() -> { loadError = R.string.library_load_failed; notifyChanged(); });
            }
        });
    }

    void observe(Listener listener) { listeners.add(listener); }
    void removeObserver(Listener listener) { listeners.remove(listener); }
    List<Recording> recordings() { return recordings; }
    boolean busy() { return operation != Operation.NONE; }
    boolean importing() { return operation == Operation.IMPORT; }

    Recording find(String id) {
        for (Recording r : recordings) if (r.id.equals(id)) return r;
        return null;
    }

    File audioFile(Recording r) { return store.audioFile(r); }

    private void notifyChanged() {
        for (Listener listener : new ArrayList<>(listeners)) listener.onLibraryChanged();
    }

    private void postNotice(int res) { notice = res; noticeSerial++; }

    private boolean begin(Operation next) {
        if (!ready || busy()) return false;
        operation = next;
        notifyChanged();
        return true;
    }

    boolean importAudio(Uri uri, int resultFlags) {
        if (!ready || busy()) return false;
        cancelImport.set(false);
        importName = ""; importedBytes = 0; expectedBytes = -1;
        if (!begin(Operation.IMPORT)) return false;
        boolean permissionTaken = false;
        if ((resultFlags & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0) {
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                permissionTaken = true;
            } catch (SecurityException ignored) { /* Current grant still permits immediate copying. */ }
        }
        final boolean releasePermission = permissionTaken;
        io.execute(() -> {
            int message;
            String imported = "";
            try {
                AudioImporter.Result result = AudioImporter.importAudio(this, store, uri, cancelImport::get,
                        (name, copied, total) -> main.post(() -> {
                            if (operation != Operation.IMPORT) return;
                            importName = name; importedBytes = copied; expectedBytes = total;
                            for (Listener listener : new ArrayList<>(listeners)) listener.onImportProgress();
                        }));
                imported = result.id;
                message = result.duplicate ? R.string.import_duplicate : R.string.import_done;
            } catch (ImportFiles.EmptyFileException e) {
                message = R.string.import_empty;
            } catch (ImportFiles.NoSpaceException e) {
                message = R.string.import_no_space;
            } catch (InterruptedIOException e) {
                message = R.string.import_cancelled;
            } catch (AudioImporter.Failure e) {
                message = e.messageRes;
            } catch (SecurityException | java.io.FileNotFoundException e) {
                message = R.string.import_unreadable;
            } catch (Exception e) {
                message = R.string.import_failed;
            } finally {
                if (releasePermission) {
                    try { getContentResolver().releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
                    catch (SecurityException ignored) { }
                }
            }
            finishOperation(message, imported);
        });
        return true;
    }

    void cancelImport() { cancelImport.set(true); }

    private void finishOperation(int message, String imported) {
        List<Recording> snapshot = null;
        try { snapshot = store.load(); }
        catch (RuntimeException ignored) { message = R.string.library_load_failed; }
        final List<Recording> updated = snapshot;
        final int status = message;
        main.post(() -> {
            if (updated != null) recordings = Collections.unmodifiableList(updated);
            if (!imported.isEmpty()) lastImportedId = imported;
            operation = Operation.NONE;
            postNotice(status);
            notifyChanged();
        });
    }

    private interface Write { void run() throws Exception; }

    private boolean write(Write task, int success) {
        if (!begin(Operation.WRITE)) return false;
        io.execute(() -> {
            int message = success;
            try { task.run(); } catch (Exception e) { message = R.string.operation_failed; }
            finishOperation(message, "");
        });
        return true;
    }

    boolean rename(String id, String name) { return write(() -> store.rename(id, name), R.string.changes_saved); }

    boolean transcribe(Recording recording, LocalTranscriber.Language language) {
        if (recording.demo || !begin(Operation.WRITE)) return false;
        processingStatus = "Подготовка обработки";
        io.execute(() -> {
            int message = R.string.changes_saved;
            try {
                LocalTranscriber.Result result =
                    new LocalTranscriber(this).transcribe(store.audioFile(recording), language,
                        status -> main.post(() -> { processingStatus = status; notifyChanged(); }));
                store.replaceTranscript(recording.id, result.getTurns(), result.getDurationMs());
                main.post(() -> processingStatus = result.getTurns().isEmpty()
                    ? "Речь не обнаружена" : "Расшифровка готова");
            } catch (Exception | LinkageError e) {
                android.util.Log.e("BIMVO", "Transcription failed", e);
                message = R.string.operation_failed;
                main.post(() -> processingStatus = "Ошибка: " + e.getMessage());
            }
            finishOperation(message, "");
        });
        return true;
    }
    boolean delete(Recording r) { return write(() -> store.delete(r), R.string.record_removed); }
    boolean updateUtterance(String id, Utterance u) {
        return write(() -> store.updateUtterance(id, u), R.string.changes_saved);
    }

    boolean prepareExport(Recording recording, String format) {
        if (recording.transcript.isEmpty() || !begin(Operation.EXPORT)) return false;
        io.execute(() -> {
            String token = UUID.randomUUID().toString();
            File file = exportFile(token);
            try {
                if (!file.getParentFile().isDirectory() && !file.getParentFile().mkdirs())
                    throw new IOException("Export directory unavailable");
                byte[] bytes = TranscriptText.format(recording.transcript, format).getBytes(StandardCharsets.UTF_8);
                try (FileOutputStream output = new FileOutputStream(file)) {
                    output.write(bytes);
                    output.getFD().sync();
                }
                String title = ImportFiles.safeDisplayName(recording.title).replaceAll("[\\\\/:*?\"<>|]", "_");
                String name = (recording.demo ? "DEMO_" : "") + title + "." + format.toLowerCase(Locale.ROOT);
                String mime = format.equals("JSON") ? "application/json"
                        : format.equals("SRT") ? "application/x-subrip" : "text/plain";
                main.post(() -> {
                    operation = Operation.NONE;
                    pendingExport = new ExportDraft(token, name, mime);
                    notifyChanged();
                });
            } catch (Exception e) {
                file.delete();
                main.post(() -> { operation = Operation.NONE; postNotice(R.string.export_failed); notifyChanged(); });
            }
        });
        return true;
    }

    ExportDraft claimExport() { ExportDraft draft = pendingExport; pendingExport = null; return draft; }

    boolean saveExport(Uri destination, String token) {
        if (!begin(Operation.EXPORT)) return false;
        io.execute(() -> {
            int message = R.string.export_saved;
            File staged = exportFile(token);
            try (FileInputStream input = new FileInputStream(staged);
                 OutputStream output = getContentResolver().openOutputStream(destination, "wt")) {
                if (output == null) throw new IOException("Export destination unavailable");
                byte[] buffer = new byte[16 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                output.flush();
            } catch (Exception e) { message = R.string.export_failed; }
            finally { staged.delete(); }
            final int status = message;
            main.post(() -> { operation = Operation.NONE; postNotice(status); notifyChanged(); });
        });
        return true;
    }

    void discardExport(String token) { io.execute(() -> exportFile(token).delete()); }

    private File exportFile(String token) {
        if (token == null || !token.matches("[a-f0-9-]{36}")) throw new IllegalArgumentException("Invalid export token");
        return new File(new File(getCacheDir(), "exports"), token + ".pending");
    }

    private void cleanExportCache() {
        File[] files = new File(getCacheDir(), "exports").listFiles();
        if (files == null) return;
        long cutoff = System.currentTimeMillis() - 86_400_000L;
        for (File file : files) if (file.lastModified() < cutoff) file.delete();
    }
}
