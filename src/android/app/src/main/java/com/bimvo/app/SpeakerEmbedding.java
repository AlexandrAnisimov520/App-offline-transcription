package com.bimvo.app;

import android.content.Context;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import java.io.IOException;
import java.util.Collections;

/** ERes2NetV2 FP32 BASIC from the supplied final archive. One session per recording. */
final class SpeakerEmbedding implements AutoCloseable {
    private final OrtEnvironment environment = OrtEnvironment.getEnvironment();
    private final OrtSession.SessionOptions options = new OrtSession.SessionOptions();
    private final OrtSession session;
    SpeakerEmbedding(Context context) throws Exception {
        options.setIntraOpNumThreads(2); options.setInterOpNumThreads(1);
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.EXTENDED_OPT);
        OrtSession opened = null;
        try {
            opened = environment.createSession(ModelAssets.map(context, "speaker_embedding_v2_basic.onnx"), options);
            TensorInfo input = (TensorInfo)opened.getInputInfo().get("x").getInfo();
            TensorInfo output = (TensorInfo)opened.getOutputInfo().get("embedding").getInfo();
            if (input.getShape().length != 3 || input.getShape()[2] != 80 || output.getShape().length != 2 || output.getShape()[1] != 192)
                throw new IOException("Некорректная модель диаризации");
            session = opened;
        } catch (Exception e) { if (opened != null) opened.close(); options.close(); throw e; }
    }
    float[] embedding(float[] waveform) throws Exception {
        return fromFeatures(SpeakerFbank.extract(waveform));
    }
    float[] fromFeatures(float[][] features) throws Exception {
        try (OnnxTensor tensor = OnnxTensor.createTensor(environment, new float[][][]{features});
             OrtSession.Result result = session.run(Collections.singletonMap("x", tensor))) {
            float[][] vector = (float[][])result.get("embedding").orElseThrow(() -> new IOException("Нет голосового вектора")).getValue();
            if (vector.length != 1 || vector[0].length != 192) throw new IOException("Неверная размерность голосового вектора");
            return vector[0].clone();
        }
    }
    @Override public void close() throws Exception { try { session.close(); } finally { options.close(); } }
}
