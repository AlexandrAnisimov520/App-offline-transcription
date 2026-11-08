package com.bimvo.app;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/** Bounded-memory, cancellable copy. No Android classes, so failure paths can be unit tested. */
public final class ImportFiles {
    public static final long FREE_SPACE_RESERVE = 16L * 1024 * 1024;
    private ImportFiles() { }

    public static final class CopyResult {
        public final long bytes;
        public final String sha256;
        private CopyResult(long bytes, String sha256) { this.bytes = bytes; this.sha256 = sha256; }
    }

    public static final class EmptyFileException extends IOException { }
    public static final class NoSpaceException extends IOException { }

    public static CopyResult copy(InputStream source, File target, BooleanSupplier cancelled,
                                  LongConsumer progress) throws IOException {
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        byte[] buffer = new byte[64 * 1024];
        long copied = 0, nextSpaceCheck = 0;
        boolean complete = false;
        // The caller owns and closes the InputStream; this method owns the partial target only.
        try (FileOutputStream out = new FileOutputStream(target)) {
            int count;
            while ((count = source.read(buffer)) != -1) {
                if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
                    throw new InterruptedIOException("Import cancelled");
                if (count == 0) continue;
                if (copied >= nextSpaceCheck) {
                    if (target.getParentFile().getUsableSpace() < FREE_SPACE_RESERVE + buffer.length)
                        throw new NoSpaceException();
                    nextSpaceCheck = copied + 1024L * 1024;
                }
                out.write(buffer, 0, count);
                digest.update(buffer, 0, count);
                copied += count;
                progress.accept(copied);
            }
            if (cancelled.getAsBoolean()) throw new InterruptedIOException("Import cancelled");
            if (copied == 0) throw new EmptyFileException();
            out.getFD().sync();
            complete = true;
        } finally {
            if (!complete && target.exists()) target.delete();
        }
        StringBuilder hash = new StringBuilder(64);
        for (byte b : digest.digest()) hash.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return new CopyResult(copied, hash.toString());
    }

    public static String safeDisplayName(String name) {
        if (name == null) return "audio";
        String clean = name.replace('\\', '/');
        clean = clean.substring(clean.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}\\u202A-\\u202E\\u2066-\\u2069]", "").trim();
        if (clean.isEmpty() || clean.equals(".") || clean.equals("..")) return "audio";
        return clean.length() > 200 ? clean.substring(0, 200) : clean;
    }
}
