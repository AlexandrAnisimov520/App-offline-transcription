package com.bimvo.app;

/** Torchaudio-compatible GigaAM v3 STFT: Hann 320, hop 160, HTK 64 mels, no CMVN. */
final class GigaamFeatures {
    private static final int FFT = 320, HOP = 160, BINS = FFT / 2 + 1, MELS = 64;
    private static final float[] WINDOW = new float[FFT];
    private static final float[][] REAL = new float[BINS][FFT];
    private static final float[][] IMAG = new float[BINS][FFT];
    private static final float[][] MEL = new float[MELS][BINS];
    static {
        for (int k = 0; k < FFT; k++) WINDOW[k] = (float) (.5 - .5 * Math.cos(2 * Math.PI * k / FFT));
        for (int b = 0; b < BINS; b++) {
            for (int k = 0; k < FFT; k++) {
                double radians = 2 * Math.PI * b * k / FFT;
                REAL[b][k] = (float) Math.cos(radians);
                IMAG[b][k] = (float) Math.sin(radians);
            }
        }
        double[] hz = new double[MELS + 2];
        double maxMel = 2595d * Math.log10(1d + 8000d / 700d);
        for (int m = 0; m < hz.length; m++)
            hz[m] = 700d * (Math.pow(10d, maxMel * m / (MELS + 1) / 2595d) - 1d);
        for (int m = 0; m < MELS; m++) {
            for (int b = 0; b < BINS; b++) {
                double freq = b * (16000d / FFT);
                double left = (freq - hz[m]) / (hz[m + 1] - hz[m]);
                double right = (hz[m + 2] - freq) / (hz[m + 2] - hz[m + 1]);
                MEL[m][b] = (float) Math.max(0d, Math.min(left, right));
            }
        }
    }
    static float[][] extract(float[] waveform) {
        if (waveform.length < FFT) throw new IllegalArgumentException("Audio chunk too short");
        int frames = (waveform.length - FFT) / HOP + 1;
        float[][] features = new float[MELS][frames];
        float[] power = new float[BINS];
        float[] frame = new float[FFT];
        for (int t = 0; t < frames; t++) {
            int offset = t * HOP;
            for (int k = 0; k < FFT; k++) frame[k] = waveform[offset + k] * WINDOW[k];
            for (int b = 0; b < BINS; b++) {
                float[] re = REAL[b], im = IMAG[b];
                double a = 0d, c = 0d;
                for (int k = 0; k < FFT; k++) { a += frame[k] * re[k]; c -= frame[k] * im[k]; }
                power[b] = (float) (a * a + c * c);
            }
            for (int m = 0; m < MELS; m++) {
                float[] weights = MEL[m];
                double sum = 0d;
                for (int b = 0; b < BINS; b++) sum += weights[b] * power[b];
                features[m][t] = (float) Math.log(Math.min(1e9, Math.max(1e-9, sum)));
            }
        }
        return features;
    }
}
