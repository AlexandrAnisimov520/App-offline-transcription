package com.bimvo.app;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Silero recurrent VAD, 16 kHz/512 samples, with 64-sample left context. */
final class SileroVad {
    static List<Long> findPauses(Context context, File pcm, BooleanSupplier cancelled,
                                 AudioDecoder.Progress progress) throws Exception {
        return analyze(context, pcm, cancelled, progress).pauses;
    }
    static final class Analysis {
        final List<Long> pauses = new ArrayList<>();
        final List<long[]> speech = new ArrayList<>();
    }
    static List<long[]> findSpeech(Context context, File pcm, BooleanSupplier cancelled,
                                   AudioDecoder.Progress progress) throws Exception {
        return analyze(context, pcm, cancelled, progress).speech;
    }
    private static void addSpeech(Analysis result, long start, long end, long total) {
        long a = Math.max(0, start - 1280), b = Math.min(total, end + 1280);
        if (end - start < 1600 || b <= a) return;
        if (!result.speech.isEmpty() && a <= result.speech.get(result.speech.size() - 1)[1])
            result.speech.get(result.speech.size() - 1)[1] = b;
        else result.speech.add(new long[]{a, b});
    }
    private static Analysis analyze(Context context, File pcm, BooleanSupplier cancelled,
                                     AudioDecoder.Progress progress) throws Exception {
        final float inputGain = VadInputGain.estimate(pcm, cancelled);
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setIntraOpNumThreads(1);
        options.setInterOpNumThreads(1);
        try (OrtSession session = env.createSession(ModelAssets.map(context, "silero_vad.onnx"), options);
             BufferedInputStream input = new BufferedInputStream(new FileInputStream(pcm), 65536)) {
            float[][][] state = new float[2][1][128];
            float[] contextSamples = new float[64];
            byte[] bytes = new byte[1024];
            Analysis analysis = new Analysis();
            List<Long> silences = analysis.pauses;
            long speechStart = -1, pendingEnd = -1;
            long cursor = 0, silentStart = -1, total = pcm.length() / 2;
            int lastPercent = -1;
            while (true) {
                if (cancelled.getAsBoolean()) throw new java.io.InterruptedIOException("Обработка отменена");
                int got = 0;
                while (got < bytes.length) {
                    int n = input.read(bytes, got, bytes.length - got);
                    if (n < 0) break;
                    got += n;
                }
                if (got == 0) break;
                float[] frame = new float[576];
                System.arraycopy(contextSamples, 0, frame, 0, 64);
                for (int i = 0; i < 512; i++) {
                    int p = 2 * i;
                    frame[64 + i] = p + 1 < got
                            ? (short) ((bytes[p] & 255) | (bytes[p + 1] << 8)) / 32768f * inputGain : 0f;
                }
                System.arraycopy(frame, 512, contextSamples, 0, 64);
                try (OnnxTensor wave = OnnxTensor.createTensor(env, new float[][]{frame});
                     OnnxTensor old = OnnxTensor.createTensor(env, state);
                     OnnxTensor sr = OnnxTensor.createTensor(env, LongBuffer.wrap(new long[]{16000L}), new long[]{})) {
                    Map<String, OnnxTensor> args = new HashMap<>();
                    args.put("input", wave); args.put("state", old); args.put("sr", sr);
                    try (OrtSession.Result result = session.run(args)) {
                        float probability = ((float[][]) result.get("output")
                                .orElseThrow(() -> new IOException("Silero output missing")).getValue())[0][0];
                        state = (float[][][]) result.get("stateN")
                                .orElseThrow(() -> new IOException("Silero stateN missing")).getValue();
                        if (speechStart < 0 && probability >= .5f) speechStart = cursor;
                        if (speechStart >= 0) {
                            if (probability < .35f) {
                                if (pendingEnd < 0) pendingEnd = cursor;
                                if (cursor + got / 2 - pendingEnd >= 3200) {
                                    addSpeech(analysis, speechStart, pendingEnd, total);
                                    speechStart = pendingEnd = -1;
                                }
                            } else pendingEnd = -1;
                        }
                        if (probability < 0.45f) {
                            if (silentStart < 0) silentStart = cursor;
                            if (cursor - silentStart >= 6L * 512L && (cursor - silentStart) % (16L * 512L) == 0)
                                silences.add(Math.min(total - 1, cursor));
                        } else if (silentStart >= 0) {
                            if (cursor - silentStart >= 6L * 512L)
                                silences.add(Math.min(total - 1, (silentStart + cursor) / 2));
                            silentStart = -1;
                        }
                    }
                }
                cursor += Math.min(512L, got / 2L);
                int percent = total == 0 ? 100 : (int) (cursor * 100L / total);
                if (percent != lastPercent) { progress.onProgress(Math.min(100, percent)); lastPercent = percent; }
            }
            if (silentStart >= 0 && cursor - silentStart >= 6L * 512L)
                silences.add(Math.min(total - 1, (silentStart + cursor) / 2));
            if (speechStart >= 0) addSpeech(analysis, speechStart, pendingEnd >= 0 ? pendingEnd : total, total);
            return analysis;
        } finally { options.close(); }
    }
}
