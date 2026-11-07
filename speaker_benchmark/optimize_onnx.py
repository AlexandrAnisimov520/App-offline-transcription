"""BIMVO: optimize speaker embedding ONNX with ONNX Runtime and verify behavior.
Requires: pip install onnxruntime onnx numpy
Usage: python optimize_onnx.py models/speaker_embedding_v2_basic.onnx
No weights retrained. Input x shape [N, T, 80] (log-mel features, not waveform).
"""
import argparse
import csv
import hashlib
import statistics
import time
from pathlib import Path

import numpy as np
import onnx
import onnxruntime as ort


def sha256(path):
    h = hashlib.sha256()
    with path.open('rb') as f:
        for block in iter(lambda: f.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def sess(path, threads, level, optimized_path=None):
    opts = ort.SessionOptions()
    opts.intra_op_num_threads = threads
    opts.inter_op_num_threads = 1
    opts.graph_optimization_level = level
    if optimized_path:
        opts.optimized_model_filepath = str(optimized_path)
    return ort.InferenceSession(str(path), sess_options=opts, providers=['CPUExecutionProvider'])


def run(s, data, iterations=7):
    inputs = s.get_inputs()
    if len(inputs) != 1:
        raise ValueError('Expected one model input; adapt preprocessing before running test.')
    feed = {inputs[0].name: data}
    s.run(None, feed)
    durations = []
    result = None
    for _ in range(iterations):
        start = time.perf_counter()
        result = s.run(None, feed)
        durations.append((time.perf_counter() - start) * 1000)
    return result, statistics.median(durations)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('model', type=Path)
    ap.add_argument('--output', type=Path, default=Path('speaker_embedding_v2_optimized.onnx'))
    ap.add_argument('--frames', type=int, default=200)
    args = ap.parse_args()
    if not args.model.is_file():
        raise FileNotFoundError(args.model)
    if args.model.resolve() == args.output.resolve():
        raise ValueError('Do not overwrite source model')
    original = onnx.load(str(args.model))
    onnx.checker.check_model(original)
    print('Original ONNX validated, sha256:', sha256(args.model))
    rng = np.random.default_rng(20261107)
    input_array = rng.normal(size=(1, args.frames, 80)).astype(np.float32)
    baseline = sess(args.model, 2, ort.GraphOptimizationLevel.ORT_DISABLE_ALL)
    if len(baseline.get_inputs()) != 1 or baseline.get_inputs()[0].type != 'tensor(float)':
        raise ValueError('Unexpected input signature')
    baseline_result, baseline_ms = run(baseline, input_array)
    print('Baseline median milliseconds:', round(baseline_ms, 2))
    export_session = sess(args.model, 2, ort.GraphOptimizationLevel.ORT_ENABLE_BASIC, args.output)
    del export_session
    if not args.output.is_file():
        raise RuntimeError('Optimized ONNX file was not created')
    optimized = onnx.load(args.output)
    onnx.checker.check_model(optimized)
    if any(node.domain not in ('', 'ai.onnx') for node in optimized.graph.node):
        args.output.unlink(missing_ok=True)
        raise RuntimeError('Optimized graph contains nonstandard operators')
    optimized_session = sess(args.output, 2, ort.GraphOptimizationLevel.ORT_DISABLE_ALL)
    optimized_result, _ = run(optimized_session, input_array)
    if len(baseline_result) != len(optimized_result):
        raise RuntimeError('Output count differs after optimization')
    for i, (a, b) in enumerate(zip(baseline_result, optimized_result)):
        err = float(np.max(np.abs(a - b)))
        print('Output', i, 'max absolute difference', err)
        if not np.allclose(a, b, rtol=1e-3, atol=1e-4):
            args.output.unlink(missing_ok=True)
            raise RuntimeError('Optimized model output differs too much; file deleted')
    print('PASS: numerical comparison against original on synthetic input')
    print('Note: synthetic test does NOT establish speaker identification accuracy / Android compatibility')
    rows = []
    for threads in (1, 2, 3, 4):
        for label, path in [('source', args.model), ('optimized', args.output)]:
            s = sess(path, threads, ort.GraphOptimizationLevel.ORT_ENABLE_EXTENDED)
            _, ms = run(s, input_array, iterations=10)
            rows.append({'model': label, 'threads': threads, 'median_ms': round(ms, 3)})
            print(label, 'threads', threads, 'median_ms', round(ms, 3))
    report = args.output.with_suffix('.benchmark.csv')
    with report.open('w', newline='', encoding='utf-8-sig') as f:
        w = csv.DictWriter(f, fieldnames=['model', 'threads', 'median_ms'])
        w.writeheader(); w.writerows(rows)
    print('Optimized ONNX:', args.output.resolve())
    print('SHA256:', sha256(args.output))
    print('Benchmark:', report.resolve())


if __name__ == '__main__':
    main()
