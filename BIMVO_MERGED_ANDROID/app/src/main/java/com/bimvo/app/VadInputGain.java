package com.bimvo.app;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.function.BooleanSupplier;

/** Bounded, recording-wide VAD gain; never rewrites the source or ASR/embedding samples. */
final class VadInputGain {
    static float estimate(File pcm, BooleanSupplier cancelled) throws IOException {
        double sum = 0, peak = 0;
        long count = 0;
        byte[] buffer = new byte[65536];
        try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(pcm))) {
            int low = -1, n;
            while ((n = input.read(buffer)) != -1) {
                SpeakerClustering.check(cancelled);
                for (int i = 0; i < n; i++) {
                    if (low < 0) { low = buffer[i] & 255; continue; }
                    double value = (short)(low | (buffer[i] << 8)) / 32768.0;
                    low = -1; sum += value * value; peak = Math.max(peak, Math.abs(value)); count++;
                }
            }
            if (low >= 0) throw new IOException("Incomplete PCM sample");
        }
        return fromLevels(count == 0 ? 0 : Math.sqrt(sum / count), peak);
    }
    static float fromLevels(double rms, double peak) {
        if (!Double.isFinite(rms) || !Double.isFinite(peak) || rms < 1e-6 || rms >= .01 || peak <= 0) return 1;
        return (float)Math.max(1, Math.min(8, Math.min(.04 / rms, .95 / peak)));
    }
}
