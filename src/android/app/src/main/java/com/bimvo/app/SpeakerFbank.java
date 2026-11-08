package com.bimvo.app;

/** Kaldi 80-bin log-power fbank: Povey 25 ms, hop 10 ms, per-window mean subtraction. */
final class SpeakerFbank {
    private static final int FRAME = 400, HOP = 160, FFT = 512, MELS = 80;
    private static final double[] WINDOW = new double[FRAME];
    private static final double[][] MEL = new double[MELS][FFT / 2];
    static {
        for (int i = 0; i < FRAME; i++) WINDOW[i] = Math.pow(.5 - .5 * Math.cos(2 * Math.PI * i / (FRAME - 1)), .85);
        double low = mel(20), high = mel(8000);
        for (int m = 0; m < MELS; m++) {
            double left = low + (high - low) * m / (MELS + 1);
            double middle = low + (high - low) * (m + 1) / (MELS + 1);
            double right = low + (high - low) * (m + 2) / (MELS + 1);
            for (int b = 0; b < FFT / 2; b++) {
                double f = mel(b * 16000.0 / FFT);
                MEL[m][b] = Math.max(0, Math.min((f - left) / (middle - left), (right - f) / (right - middle)));
            }
        }
    }
    private static double mel(double hz) { return 1127 * Math.log1p(hz / 700); }

    static float[][] extract(float[] wave) {
        if (wave.length < FRAME) throw new IllegalArgumentException("Speaker window shorter than 25 ms");
        int frames = (wave.length - FRAME) / HOP + 1;
        float[][] features = new float[frames][MELS];
        double[] means = new double[MELS];
        double[] real = new double[FFT], imag = new double[FFT], frame = new double[FRAME];
        for (int t = 0; t < frames; t++) {
            double dc = 0;
            for (int k = 0; k < FRAME; k++) dc += wave[t * HOP + k];
            dc /= FRAME;
            for (int k = 0; k < FRAME; k++) frame[k] = wave[t * HOP + k] - dc;
            for (int k = FRAME - 1; k > 0; k--) frame[k] -= .97 * frame[k - 1];
            frame[0] *= .03;
            java.util.Arrays.fill(real, 0); java.util.Arrays.fill(imag, 0);
            for (int k = 0; k < FRAME; k++) real[k] = frame[k] * WINDOW[k];
            fft(real, imag);
            for (int m = 0; m < MELS; m++) {
                double sum = 0;
                for (int b = 0; b < FFT / 2; b++) sum += MEL[m][b] * (real[b] * real[b] + imag[b] * imag[b]);
                features[t][m] = (float)Math.log(Math.max(1.1920928955078125e-7, sum));
                means[m] += features[t][m];
            }
        }
        for (float[] row : features) for (int m = 0; m < MELS; m++) row[m] -= means[m] / frames;
        return features;
    }

    private static void fft(double[] real, double[] imag) {
        for (int i = 1, j = 0; i < FFT; i++) {
            int bit = FFT >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) { double value = real[i]; real[i] = real[j]; real[j] = value; }
        }
        for (int len = 2; len <= FFT; len <<= 1) {
            double angle = -2 * Math.PI / len, stepR = Math.cos(angle), stepI = Math.sin(angle);
            for (int start = 0; start < FFT; start += len) {
                double wr = 1, wi = 0;
                for (int k = 0; k < len / 2; k++) {
                    int a = start + k, b = a + len / 2;
                    double br = real[b] * wr - imag[b] * wi, bi = real[b] * wi + imag[b] * wr;
                    real[b] = real[a] - br; imag[b] = imag[a] - bi;
                    real[a] += br; imag[a] += bi;
                    double next = wr * stepR - wi * stepI;
                    wi = wr * stepI + wi * stepR; wr = next;
                }
            }
        }
    }
}
