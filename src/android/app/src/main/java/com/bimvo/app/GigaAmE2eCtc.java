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
        return transcribeDetailed(audio, chunk).getText();
    }
    com.bimvo.app.diarization.AsrResult transcribeDetailed(RandomAccessFile audio, ChunkPlanner.Chunk chunk) throws Exception {
        float[] signal = AudioDecoder.readPcm(audio, chunk.start, chunk.end);
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
                java.util.List<String> tokens = new java.util.ArrayList<>();
                java.util.List<Float> times = new java.util.ArrayList<>();
                int frameIndex = 0;
                int prev = 256;
                for (float[] row : probabilities[0]) {
                    int best = 0;
                    for (int i = 1; i < row.length; i++) if (row[i] > row[best]) best = i;
                    if (best != 256 && best != prev && best < vocabulary.length && vocabulary[best] != null
                            && best != 0 && !vocabulary[best].startsWith("<"))
                    {
                        sentence.append(vocabulary[best]);
                        tokens.add(vocabulary[best]);
                        // Same frame-time mapping as GigaAM compute_frame_shift(audio_samples, output_frames).
                        times.add(Math.min(signal.length / 16000f, frameIndex * (signal.length / 16000f / probabilities[0].length)));
                    }
                    frameIndex++;
                    prev = best;
                }
                float[] timestamps = new float[times.size()];
                for (int i = 0; i < timestamps.length; i++) timestamps[i] = times.get(i);
                return new com.bimvo.app.diarization.AsrResult(sentence.toString().replace('▁', ' ').trim(),
                        tokens.toArray(new String[0]), timestamps);
            }
        }
    }

    @Override public void close() throws Exception {
        try { session.close(); } finally { options.close(); }
    }
}
