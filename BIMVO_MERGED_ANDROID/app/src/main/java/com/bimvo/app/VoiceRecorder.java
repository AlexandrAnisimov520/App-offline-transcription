package com.bimvo.app;

import android.content.Context;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.SystemClock;

import java.io.File;
import java.io.IOException;

/** Device-local AAC/M4A capture. Uses cache staging, never writes into LibraryStore directly. */
final class VoiceRecorder {
    private MediaRecorder recorder;
    private File stagedFile;
    private long startedAt;

    boolean isRecording() { return recorder != null; }
    long elapsedMs() { return isRecording() ? SystemClock.elapsedRealtime() - startedAt : 0; }

    void start(Context context) throws IOException {
        if (isRecording()) throw new IllegalStateException("Already recording");
        File dir = new File(context.getCacheDir(), "voice-capture");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create recording cache");
        File target = File.createTempFile("voice_", ".m4a", dir);
        MediaRecorder candidate = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(context) : new MediaRecorder();
        try {
            candidate.setAudioSource(MediaRecorder.AudioSource.MIC);
            candidate.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            candidate.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            candidate.setAudioChannels(1);
            candidate.setAudioSamplingRate(16000);
            candidate.setAudioEncodingBitRate(64000);
            candidate.setOutputFile(target.getAbsolutePath());
            candidate.prepare();
            candidate.start();
            recorder = candidate;
            stagedFile = target;
            startedAt = SystemClock.elapsedRealtime();
        } catch (RuntimeException | IOException error) {
            candidate.release();
            target.delete();
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("Microphone capture could not start", error);
        }
    }

    /** Returns a complete file, owned by the caller. Throws on too-short/broken recordings. */
    File stop() throws IOException {
        if (!isRecording()) throw new IOException("Recording not started");
        File target = stagedFile;
        long elapsed = elapsedMs();
        try {
            if (elapsed < 750) throw new IOException("Запись слишком короткая (меньше секунды)");
            recorder.stop();
            if (target == null || !target.isFile() || target.length() == 0)
                throw new IOException("Пустая запись");
            return target;
        } catch (RuntimeException e) {
            if (target != null) target.delete();
            throw new IOException("Ошибка записи. Попробуйте ещё раз", e);
        } catch (IOException e) {
            if (target != null) target.delete();
            throw e;
        } finally {
            recorder.reset();
            recorder.release();
            recorder = null;
            stagedFile = null;
            startedAt = 0;
        }
    }

    void discard() {
        if (recorder != null) {
            try { recorder.stop(); } catch (RuntimeException ignored) { }
            try { recorder.release(); } catch (RuntimeException ignored) { }
            recorder = null;
        }
        if (stagedFile != null) stagedFile.delete();
        stagedFile = null;
        startedAt = 0;
    }
}
