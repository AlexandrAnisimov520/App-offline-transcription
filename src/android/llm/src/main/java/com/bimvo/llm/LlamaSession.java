package com.bimvo.llm;

import java.nio.charset.StandardCharsets;

/** One worker owns generate/close. cancel may run concurrently on the UI thread. */
public final class LlamaSession implements AutoCloseable {
    static { System.loadLibrary("bimvo_llama"); }
    private long handle = nativeCreate();
    public interface Listener {
        // UTF-8 may end inside a codepoint until the next callback; final text is complete.
        void onProgress(byte[] utf8, int generatedTokens);
    }
    public String generate(String path, String transcript, Listener listener) {
        return new String(nativeGenerate(handle, path.getBytes(StandardCharsets.UTF_8),
                transcript.getBytes(StandardCharsets.UTF_8), listener), StandardCharsets.UTF_8);
    }
    public synchronized void cancel() { if (handle != 0) nativeCancel(handle); }
    @Override public synchronized void close() {
        if (handle != 0) { nativeDestroy(handle); handle = 0; }
    }
    private static native long nativeCreate();
    private static native void nativeCancel(long handle);
    private static native void nativeDestroy(long handle);
    private static native byte[] nativeGenerate(long handle, byte[] path, byte[] text, Listener listener);
}
