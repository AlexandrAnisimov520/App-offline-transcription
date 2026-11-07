package com.bimvo.app;

import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Serial VAD->speaker embedding->global clustering->speaker-aware ASR pipeline; no network, no fake result, restartable on persistent chunk boundaries. */
final class AudioProcessingManager {
    private final BimvoApplication application;
    private final LibraryStore store;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicReference<String> activeId = new AtomicReference<>("");
    private final AtomicBoolean cancelled = new AtomicBoolean();

    AudioProcessingManager(BimvoApplication app, LibraryStore store) {
        this.application = app;
        this.store = store;
    }

    boolean isRunning(String id) { return id != null && id.equals(activeId.get()); }

    void cancel(String id) {
        if (isRunning(id)) cancelled.set(true);
    }

    boolean execute(String id, Runnable finished) {
        if (id == null || id.isEmpty() || !activeId.compareAndSet("", id)) return false;
        cancelled.set(false);
        executor.execute(() -> {
            try { process(id); }
            finally {
                activeId.set("");
                application.refreshAfterProcessing();
                if (finished != null) finished.run();
            }
        });
        return true;
    }

    private void report(String id, String stage, int percent) {
        store.updateJob(id, "running", stage, percent, "");
        application.reportProcessing(id, store.job(id), false);
    }

    private void process(String id) {
        try {
            if (!store.queueJob(id)) return;
            report(id,"prepare", 0);
            Recording record = null;
            for (Recording r : store.load()) if (r.id.equals(id) && !r.demo) { record = r; break; }
            if (record == null) throw new IOException("Запись не найдена");
            File original = store.audioFile(record);
            if (!original.isFile()) throw new IOException("Оригинальный аудиофайл отсутствует");
            File pcm = store.processingFile(id);
            if (!pcm.getParentFile().isDirectory() && !pcm.getParentFile().mkdirs())
                throw new IOException("Не удалось создать рабочую папку");
            if (pcm.isFile() && (pcm.length() < 640 || (pcm.length() & 1) != 0)) pcm.delete();
            if (!pcm.isFile()) {
                if (original.length() * 8L > pcm.getParentFile().getUsableSpace() &&
                        pcm.getParentFile().getUsableSpace() < 32L * 1024 * 1024)
                    throw new IOException("Недостаточно места для обработки аудио");
                final int[] last = {-1};
                AudioDecoder.decode(original, pcm, cancelled::get, percent -> {
                    int value = Math.min(14, percent * 14 / 100);
                    if (value != last[0]) { report(id,"decode", value); last[0] = value; }
                });
            }
            checkCancel();
            long totalSamples = pcm.length() / 2;
            if (totalSamples < 320) throw new IOException("Слишком короткая аудиозапись");
            List<ChunkPlanner.Chunk> parts = store.chunks(id);
            if (!parts.isEmpty() && (parts.get(parts.size()-1).end > totalSamples ||
                    !SpeakerDiarization.VERSION.equals(parts.get(0).reason))) parts = Collections.emptyList();
            if (parts.isEmpty()) {
                parts = SpeakerDiarization.plan(application, pcm, cancelled::get, (stage, percent) -> report(id, stage, percent));
                checkCancel();
                store.setChunkPlan(id, parts);
            }
            report(id,"asr", Math.max(60, store.job(id).percent));
            Set<Integer> done = store.completedChunks(id);
            if (done.size() != parts.size()) {
                // Close the VAD session before allocating the 225 MB ASR model.
                try (RandomAccessFile audio = new RandomAccessFile(pcm,"r");
                     GigaAmE2eCtc asr = new GigaAmE2eCtc(application)) {
                    for (ChunkPlanner.Chunk chunk : parts) {
                        if (done.contains(chunk.index)) continue;
                        checkCancel();
                        String text = asr.transcribe(audio,chunk);
                        checkCancel();
                        store.finishChunk(id, chunk, text);
                        application.reportProcessing(id, store.job(id), true);
                    }
                }
            }
            checkCancel();
            store.completeAudioJob(id);
            application.reportProcessing(id,store.job(id),true);
            pcm.delete(); // Original is never deleted; PCM can be regenerated for later models.
        } catch (InterruptedIOException e) {
            LibraryStore.Job previous = store.job(id);
            store.updateJob(id,"paused","paused",previous == null ? 0 : previous.percent,"");
            application.reportProcessing(id,store.job(id),true);
        } catch (Exception | LinkageError error) {
            Log.e("BIMVO", "Offline transcription failed", error);
            LibraryStore.Job previous = store.job(id);
            String message = error.getMessage() == null ? "Ошибка локального распознавания" : error.getMessage();
            store.updateJob(id,"failed","failed",previous == null ? 0 : previous.percent,message);
            application.reportProcessing(id,store.job(id),true);
        }
    }

    private void checkCancel() throws InterruptedIOException {
        if (cancelled.get() || Thread.currentThread().isInterrupted())
            throw new InterruptedIOException("Пользователь отменил обработку");
    }
}
