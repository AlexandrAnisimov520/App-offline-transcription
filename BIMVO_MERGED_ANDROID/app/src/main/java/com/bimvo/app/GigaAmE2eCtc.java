package com.bimvo.app;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import android.content.Context;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** GigaAM-v3 E2E CTC, ONNX CPU, greedy collapse, SentencePiece vocabulary. */
final class GigaAmE2eCtc implements AutoCloseable {
    private final OrtEnvironment env;
    private final OrtSession session;
    private final String[] vocabulary = new String[257];
    private final OrtSession.SessionOptions options;

    GigaAmE2eCtc(Context context) throws Exception {
        env = OrtEnvironment.getEnvironment();
        options = new OrtSession.SessionOptions();
        options.setInterOpNumThreads(1);
        options.setIntraOpNumThreads(2);
        // Read tiny vocabulary before creating the expensive session.
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                context.getAssets().open("models/v3_e2e_ctc_vocab.txt"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int space = line.lastIndexOf(' ');
                if (space < 0) continue;
                int id = Integer.parseInt(line.substring(space + 1).trim());
                if (id >= 0 && id < vocabulary.length) vocabulary[id] = line.substring(0, space);
            }
        }
        if (vocabulary[1] == null || !"<blk>".equals(vocabulary[256]))
            throw new IOException("Неверный словарь GigaAM");
        session = env.createSession(ModelAssets.map(context, "v3_e2e_ctc.int8.onnx"), options);
    }

    String transcribe(RandomAccessFile audio, ChunkPlanner.Chunk chunk) throws Exception {
        long samples = chunk.end - chunk.start;
        if (samples < 320 || samples > ChunkPlanner.LIMIT)
            throw new IllegalArgumentException("Invalid ASR chunk size");
        float[] signal = new float[(int) samples];
        audio.seek(chunk.start * 2);
        byte[] read = new byte[Math.min(65536, (int) samples * 2)];
        for (int pos = 0; pos < signal.length;) {
            int count = Math.min(signal.length - pos, read.length / 2);
            audio.readFully(read, 0, count * 2);
            for (int j = 0; j < count; j++) {
                int at = j * 2;
                signal[pos + j] = (short) ((read[at] & 255) | (read[at + 1] << 8)) / 32768f;
            }
            pos += count;
        }
        float[][] features = GigaamFeatures.extract(signal);
        int frames = features[0].length;
        try (OnnxTensor tensor = OnnxTensor.createTensor(env, new float[][][]{features});
             OnnxTensor lengths = OnnxTensor.createTensor(env, new long[]{frames})) {
            Map<String, OnnxTensor> args = new HashMap<>();
            args.put("features", tensor);
            args.put("feature_lengths", lengths);
            try (OrtSession.Result result = session.run(args)) {
                float[][][] probabilities = (float[][][]) result.get("log_probs")
                        .orElseThrow(() -> new IOException("GigaAM log_probs missing")).getValue();
                StringBuilder sentence = new StringBuilder();
                int prev = 256;
                for (float[] row : probabilities[0]) {
                    int best = 0;
                    for (int i = 1; i < row.length; i++) if (row[i] > row[best]) best = i;
                    if (best != 256 && best != prev && best < vocabulary.length && vocabulary[best] != null
                            && best != 0 && !vocabulary[best].startsWith("<"))
                        sentence.append(vocabulary[best]);
                    prev = best;
                }
                return sentence.toString().replace('▁', ' ').trim();
            }
        }
    }

    @Override public void close() throws Exception {
        try { session.close(); } finally { options.close(); }
    }
}
