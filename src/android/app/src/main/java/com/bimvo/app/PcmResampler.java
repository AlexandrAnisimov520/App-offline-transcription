package com.bimvo.app;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/** Streaming mono resampler: 32-tap windowed-sinc low-pass with 1024 subphases. */
final class PcmResampler {
    private static final int RADIUS = 16;
    private static final int PHASES = 1024;
    private static final int RING_SIZE = 8192;
    private final float[][] filters = new float[PHASES][RADIUS * 2];
    private final float[] ring = new float[RING_SIZE];
    private final OutputStream output;
    private final double step;
    private long received, written;
    private double position;

    PcmResampler(int inputRate, OutputStream output) {
        if (inputRate < 4_000 || inputRate > 192_000) throw new IllegalArgumentException("Bad sample rate");
        this.output = output instanceof BufferedOutputStream ? output : new BufferedOutputStream(output, 65536);
        step = (double) inputRate / ChunkPlanner.SAMPLE_RATE;
        double cutoff = Math.min(1d, 1d / step) * 0.94;
        for (int phase = 0; phase < PHASES; phase++) {
            double fraction = (double) phase / PHASES;
            double norm = 0;
            for (int k = 0; k < RADIUS * 2; k++) {
                double offset = k - RADIUS + 1 - fraction;
                double x = Math.PI * offset * cutoff;
                double sinc = Math.abs(x) < 1e-10 ? 1d : Math.sin(x) / x;
                double w = 0.5 * (1d + Math.cos(Math.PI * offset / RADIUS));
                double weight = sinc * cutoff * Math.max(0d, w);
                filters[phase][k] = (float) weight;
                norm += weight;
            }
            for (int k = 0; k < RADIUS * 2; k++) filters[phase][k] /= (float) norm;
        }
    }

    void add(float sample) throws IOException {
        ring[(int) (received % RING_SIZE)] = sample;
        received++;
        emit(false);
    }

    long finish() throws IOException {
        emit(true);
        output.flush();
        return written;
    }

    private void emit(boolean last) throws IOException {
        long outputSamples = Math.round(received / step);
        while ((last && written < outputSamples) || (!last && position + RADIUS < received)) {
            long center = (long) Math.floor(position);
            int phase = Math.min(PHASES - 1, (int) ((position - center) * PHASES));
            float[] filter = filters[phase];
            double sum = 0;
            for (int k = 0; k < filter.length; k++) {
                long sampleIndex = Math.max(0, Math.min(received - 1, center + k - RADIUS + 1));
                if (received - sampleIndex >= RING_SIZE)
                    throw new IOException("Resampler ring exhausted");
                sum += ring[(int) (sampleIndex % RING_SIZE)] * filter[k];
            }
            int s = Math.round((float) (Math.max(-1d, Math.min(1d, sum)) * 32767d));
            output.write(s & 255);
            output.write((s >>> 8) & 255);
            position += step;
            written++;
        }
    }
}
