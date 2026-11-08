package com.bimvo.app;

import android.util.Log;

import java.io.File;
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
            if (record == null) throw new IOException("Р—Р°РїРёСЃСЊ РЅРµ РЅР°Р№РґРµРЅР°");
            File original = store.audioFile(record);
            if (!original.isFile()) throw new IOException("РћСЂРёРіРёРЅР°Р»СЊРЅС‹Р№ Р°СѓРґРёРѕС„Р°Р№Р» РѕС‚СЃСѓС‚СЃС‚РІСѓРµС‚");
            File pcm = store.processingFile(id);
            if (!pcm.getParentFile().isDirectory() && !pcm.getParentFile().mkdirs())
                throw new IOException("РќРµ СѓРґР°Р»РѕСЃСЊ СЃРѕР·РґР°С‚СЊ СЂР°Р±РѕС‡СѓСЋ РїР°РїРєСѓ");
            if (pcm.isFile() && (pcm.length() < 640 || (pcm.length() & 1) != 0)) pcm.delete();
            if (!pcm.isFile()) {
                if (original.length() * 8L > pcm.getParentFile().getUsableSpace() &&
                        pcm.getParentFile().getUsableSpace() < 32L * 1024 * 1024)
                    throw new IOException("РќРµРґРѕСЃС‚Р°С‚РѕС‡РЅРѕ РјРµСЃС‚Р° РґР»СЏ РѕР±СЂР°Р±РѕС‚РєРё Р°СѓРґРёРѕ");
                final int[] last = {-1};
                AudioDecoder.decode(original, pcm, cancelled::get, percent -> {
                    int value = Math.min(14, percent * 14 / 100);
                    if (value != last[0]) { report(id,"decode", value); last[0] = value; }
                });
            }
            checkCancel();
            long totalSamples = pcm.length() / 2;
            if (totalSamples < 320) throw new IOException("РЎР»РёС€РєРѕРј РєРѕСЂРѕС‚РєР°СЏ Р°СѓРґРёРѕР·Р°РїРёСЃСЊ");
            List<ChunkPlanner.Chunk> parts = store.chunks(id);
            if (!parts.isEmpty() && (parts.get(parts.size()-1).end > totalSamples ||
                    parts.stream().anyMatch(chunk -> !BilingualAsr.hasLanguage(chunk)))) parts = Collections.emptyList();
            if (parts.isEmpty()) {
                DiarizationResult diarization = SpeakerDiarization.diarize(application, pcm, cancelled::get, (stage, percent) -> report(id, stage, percent));
                parts = diarization.original;
                report(id, "language", 56);
                try (RandomAccessFile audio = new RandomAccessFile(pcm, "r")) {
                    parts = BilingualAsr.assignLanguages(application, audio, parts, cancelled::get, application.jobAsrLanguage(id));
                }
                checkCancel();
                parts = new DiarizationResult(diarization.intervals, parts).chunks;
                store.setChunkPlan(id, parts, diarization.intervals);
            }
            report(id,"asr", Math.max(60, store.job(id).percent));
            Set<Integer> done = store.completedChunks(id);
            if (done.size() != parts.size()) {
                try (RandomAccessFile audio = new RandomAccessFile(pcm, "r")) {
                    if (parts.stream().anyMatch(chunk -> !done.contains(chunk.index) && !BilingualAsr.isEnglish(chunk))) {
                        checkCancel();
                        try (GigaAmE2eCtc russian = new GigaAmE2eCtc(application)) {
                            transcribe(id, audio, parts, done, false, russian::transcribe);
                        }
                    }
                    if (parts.stream().anyMatch(chunk -> !done.contains(chunk.index) && BilingualAsr.isEnglish(chunk))) {
                        checkCancel();
                        try (BilingualAsr english = new BilingualAsr(application)) {
                            transcribe(id, audio, parts, done, true, english::transcribe);
                        }
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
            String message = error.getMessage() == null ? "РћС€РёР±РєР° Р»РѕРєР°Р»СЊРЅРѕРіРѕ СЂР°СЃРїРѕР·РЅР°РІР°РЅРёСЏ" : error.getMessage();
            store.updateJob(id,"failed","failed",previous == null ? 0 : previous.percent,message);
            application.reportProcessing(id,store.job(id),true);
        }
    }

    @FunctionalInterface
    private interface Recognizer {
        String transcribe(RandomAccessFile audio, ChunkPlanner.Chunk chunk) throws Exception;
    }

    private void transcribe(String id, RandomAccessFile audio, List<ChunkPlanner.Chunk> parts,
            Set<Integer> done, boolean english, Recognizer recognizer) throws Exception {
        for (ChunkPlanner.Chunk chunk : parts) {
            if (done.contains(chunk.index) || BilingualAsr.isEnglish(chunk) != english) continue;
            checkCancel();
            String text = recognizer.transcribe(audio, chunk);
            checkCancel();
            store.finishChunk(id, chunk, text);
            application.reportProcessing(id, store.job(id), true);
        }
    }

    private void checkCancel() throws InterruptedIOException {
        if (cancelled.get() || Thread.currentThread().isInterrupted())
            throw new InterruptedIOException("РџРѕР»СЊР·РѕРІР°С‚РµР»СЊ РѕС‚РјРµРЅРёР» РѕР±СЂР°Р±РѕС‚РєСѓ");
    }
}
