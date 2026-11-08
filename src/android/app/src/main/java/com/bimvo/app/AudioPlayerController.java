package com.bimvo.app;

import android.media.MediaPlayer;
import java.io.File;
import java.io.IOException;

/** Plays local imported audio, never simulates playback. Call on the UI thread. */
final class AudioPlayerController {
    private MediaPlayer player;
    private String loadedId;

    boolean isLoaded(String id) { return player != null && id != null && id.equals(loadedId); }

    void load(String id, File file) throws IOException {
        release();
        if (file == null || !file.isFile() || !file.canRead()) throw new IOException("Файл отсутствует");
        MediaPlayer next = new MediaPlayer();
        try {
            next.setDataSource(file.getAbsolutePath());
            next.prepare();
            player = next;
            loadedId = id;
        } catch (IOException | RuntimeException e) {
            next.release();
            throw e;
        }
    }
    void play() { if (player != null) player.start(); }
    void pause() { if (player != null && player.isPlaying()) player.pause(); }
    boolean isPlaying() { return player != null && player.isPlaying(); }
    long positionMs() { return player == null ? 0 : player.getCurrentPosition(); }
    long durationMs() { return player == null ? 0 : player.getDuration(); }
    void seekTo(long ms) { if (player != null) player.seekTo((int)Math.min(Integer.MAX_VALUE, Math.max(0,ms))); }
    void release() { if (player != null) { player.release(); player=null; } loadedId=null; }
}
