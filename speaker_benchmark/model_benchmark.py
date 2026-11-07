"""Export and audit ERes2NetV2 variants; benchmark each in a fresh process."""
import argparse
import csv
import hashlib
import json
from pathlib import Path
import statistics
import subprocess
import sys
import time

import numpy as np
import onnx
import onnxruntime as ort
import psutil

from pipeline import embedding_input, make_session

MODEL = Path('models/speaker_embedding_v2_basic.onnx')
PORTABLE = Path('models/speaker_embedding_v2_optimized.onnx')
INT8 = Path('runs/speaker_embedding_v2_int8_qdq.onnx')
REPORT = Path('reports')


def sha256(path):
    with path.open('rb') as file:
        return hashlib.file_digest(file, 'sha256').hexdigest()


def features(path):
    with np.load(path) as cache:
        return [cache[key] for key in cache.files]


def graph_info(path):
    model = onnx.load(path)
    onnx.checker.check_model(model)
    domains = sorted({node.domain or 'ai.onnx' for node in model.graph.node})
    return dict(file=str(path), sha256=sha256(path), bytes=path.stat().st_size,
                nodes=len(model.graph.node), domains=domains, ir_version=model.ir_version,
                opsets={op.domain or 'ai.onnx': op.version for op in model.opset_import})


def export(calibration):
    REPORT.mkdir(exist_ok=True)
    PORTABLE.parent.mkdir(exist_ok=True)
    INT8.parent.mkdir(exist_ok=True)
    temp = PORTABLE.with_suffix('.tmp.onnx')
    try:
        session = make_session(MODEL, 1, 'basic', temp)
        name = embedding_input(session)
        del session
        info = graph_info(temp)
        if info['domains'] != ['ai.onnx']:
            raise ValueError(f'Unexpected domains: {info["domains"]}')
        temp.replace(PORTABLE)
    finally:
        temp.unlink(missing_ok=True)
    from onnxruntime.quantization import (
        CalibrationDataReader, QuantFormat, QuantType, quantize_static,
    )

    class Reader(CalibrationDataReader):
        def __init__(self, tensors):
            self.tensors = iter(tensors)

        def get_next(self):
            tensor = next(self.tensors, None)
            return {name: tensor} if tensor is not None else None

    # Per-channel S8S8, standard ONNX Q/DQ. Conv dominates this network.
    quantize_static(PORTABLE, INT8, Reader(features(calibration)),
                    quant_format=QuantFormat.QDQ, activation_type=QuantType.QInt8,
                    weight_type=QuantType.QInt8, per_channel=True, op_types_to_quantize=['Conv'])
    information = [graph_info(path) for path in (MODEL, PORTABLE, INT8)]
    (REPORT / 'graphs.json').write_text(json.dumps(information, indent=2), encoding='utf-8')


def worker(path, level, threads, tensors, output):
    process = psutil.Process()
    before = process.memory_info().rss
    start = time.perf_counter()
    session = make_session(path, threads, level)
    load_ms = (time.perf_counter() - start) * 1000
    name = embedding_input(session)
    records = []
    for tensor in features(tensors):
        feed = {name: tensor}
        session.run(None, feed)
        durations = []
        for _ in range(4):
            start = time.perf_counter()
            session.run(None, feed)
            durations.append((time.perf_counter() - start) * 1000)
        records.append((tensor.shape[1], statistics.median(durations)))
    mem = process.memory_info()
    data = dict(load_ms=load_ms, start_rss_mib=before / 2**20,
                final_rss_mib=mem.rss / 2**20, peak_rss_mib=mem.peak_wset / 2**20
                if hasattr(mem, 'peak_wset') else None, model_mib=path.stat().st_size / 2**20)
    for length, latency in records:
        data[f'median_ms_t{length}'] = latency
    output.write_text(json.dumps(data), encoding='utf-8')


def numerical(tensors, variants):
    rng = np.random.default_rng(73093)
    inputs = features(tensors)
    audio_count = len(inputs)
    for frames in (23, 38, 98, 198, 398):
        x = rng.normal(size=(1, frames, 80)).astype(np.float32)
        inputs.append(x - x.mean(axis=1, keepdims=True))

    def infer(path, level):
        s = make_session(path, 1, level)
        name = embedding_input(s)
        return np.stack([s.run(None, {name: x})[0][0] for x in inputs])

    def unit(x):
        return x.astype(np.float64) / np.linalg.norm(x.astype(np.float64), axis=1, keepdims=True)

    reference = infer(MODEL, 'off')
    ref_unit = unit(reference)
    output = []
    for variant, path, level in variants:
        predicted = infer(path, level)
        pred_unit = unit(predicted)
        for group, selection in (('speech', slice(0, audio_count)),
                                 ('synthetic', slice(audio_count, None))):
            a, b = ref_unit[selection], pred_unit[selection]
            output.append(dict(variant=variant, inputs=group, count=len(a),
                               finite=bool(np.isfinite(predicted[selection]).all()),
                               max_abs=float(np.max(np.abs(predicted[selection] - reference[selection]))),
                               min_embedding_cosine=float(np.min(np.sum(a * b, axis=1))),
                               max_pairwise_cosine_delta=float(np.max(np.abs(a @ a.T - b @ b.T))),
                               fp32_allclose=bool(np.allclose(reference[selection], predicted[selection],
                                                            atol=1e-4, rtol=1e-3))))
    write_csv(REPORT / 'numerical.csv', output)


def write_csv(path, records):
    with path.open('w', newline='', encoding='utf-8') as file:
        writer = csv.DictWriter(file, fieldnames=list(records[0]))
        writer.writeheader()
        writer.writerows(records)


def benchmark(tensors, bench_inputs):
    variants = [('original_off', MODEL, 'off'), ('original_extended', MODEL, 'extended'),
                ('original_all_runtime', MODEL, 'all'), ('fp32_basic_off', PORTABLE, 'off'),
                ('fp32_basic_extended', PORTABLE, 'extended')]
    if INT8.exists():
        variants.append(('int8_qdq_extended', INT8, 'extended'))
    numerical(tensors, variants)
    report = REPORT / 'benchmark.csv'
    temp = REPORT / 'worker.json'
    records = []
    for threads in (1, 2, 3, 4):
        for variant, path, level in variants:
            subprocess.run([sys.executable, __file__, 'worker', str(path), level, str(threads),
                            str(bench_inputs), str(temp)], check=True)
            row = dict(variant=variant, threads=threads, **json.loads(temp.read_text()))
            records.append(row)
            write_csv(report, records)
            print(variant, threads, {k: round(v, 2) for k, v in row.items()
                                    if k.startswith('median_ms')}, flush=True)
    temp.unlink(missing_ok=True)
    print('ONNX Runtime', ort.__version__)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    p = commands.add_parser('export')
    p.add_argument('calibration', type=Path)
    p = commands.add_parser('benchmark')
    p.add_argument('test_features', type=Path)
    p.add_argument('benchmark_inputs', type=Path)
    p = commands.add_parser('worker')
    p.add_argument('model', type=Path)
    p.add_argument('level')
    p.add_argument('threads', type=int)
    p.add_argument('inputs', type=Path)
    p.add_argument('output', type=Path)
    args = parser.parse_args()
    if args.command == 'export':
        export(args.calibration)
    elif args.command == 'benchmark':
        benchmark(args.test_features, args.benchmark_inputs)
    else:
        worker(args.model, args.level, args.threads, args.inputs, args.output)


if __name__ == '__main__':
    main()
