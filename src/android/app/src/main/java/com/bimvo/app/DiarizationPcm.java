package com.bimvo.app;

import java.io.*;
import java.nio.file.*;
import java.util.function.BooleanSupplier;

/** External diarization DC removal and peak/RMS gain, streamed into derived PCM; original untouched. */
final class DiarizationPcm {
    private static void check(BooleanSupplier cancelled) throws InterruptedIOException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
            throw new InterruptedIOException("Обработка отменена");
    }
    static void normalize(File source, File target, BooleanSupplier cancelled) throws IOException {
        long count = source.length() / 2;
        if (count == 0 || source.length() % 2 != 0) throw new IOException("Invalid PCM");
        double sum = 0;
        try (DataInputStream in = input(source)) {
            for (long i = 0; i < count; i++) {
                if (i % 8192 == 0) check(cancelled);
                sum += Short.reverseBytes(in.readShort()) / 32768f;
            }
        }
        double mean = sum / count, square = 0;
        float peak = 0;
        try (DataInputStream in = input(source)) {
            for (long i = 0; i < count; i++) {
                if (i % 8192 == 0) check(cancelled);
                float v = (float)(Short.reverseBytes(in.readShort()) / 32768f - mean);
                peak = Math.max(peak, Math.abs(v)); square += v * v;
            }
        }
        float rms = (float)Math.sqrt(square / count);
        float gain = peak < 1e-4f ? 1f : .9f / peak;
        if (peak >= 1e-4f && rms > 0) gain = Math.min(gain, .2f / rms);
        gain = Math.max(1f, Math.min(10f, gain));
        if (gain <= 1.05f) gain = 1f;
        File partial = new File(target.getPath() + ".normalizing");
        try {
            try (DataInputStream in = input(source);
                 FileOutputStream file = new FileOutputStream(partial);
                 DataOutputStream out = new DataOutputStream(new BufferedOutputStream(file))) {
                for (long i = 0; i < count; i++) {
                    if (i % 8192 == 0) check(cancelled);
                    float v = (float)(Short.reverseBytes(in.readShort()) / 32768f - mean) * gain;
                    int value = Math.max(-32768, Math.min(32767, Math.round(v * 32768f)));
                    out.writeShort(Short.reverseBytes((short)value));
                }
                out.flush(); file.getFD().sync();
            }
            check(cancelled);
            Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally { partial.delete(); }
    }
    private static DataInputStream input(File f) throws IOException {
        return new DataInputStream(new BufferedInputStream(new FileInputStream(f), 65536));
    }
}
