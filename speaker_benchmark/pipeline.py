"""Offline diarization from speech intervals or local Pyannote segmentation."""
import argparse
import csv
import json
import time
from pathlib import Path
import numpy as np
import soundfile as sf
from scipy.signal import resample_poly
from math import gcd
from clustering import Segment, global_cluster
from features import extract_fbank
from intervals import RATE, prepare_turns


def read_audio(path):
    audio, sr = sf.read(path, dtype='float32', always_2d=True)
    audio = np.mean(audio, axis=1)
    if sr != 16000:
        div = gcd(sr, 16000)
        audio = resample_poly(audio, 16000 // div, sr // div).astype(np.float32)
    return audio


def make_session(path, threads, optimization='extended', save_graph=None):
    import onnxruntime as ort
    opts = ort.SessionOptions()
    opts.intra_op_num_threads = threads
    opts.inter_op_num_threads = 1
    levels = {'off': ort.GraphOptimizationLevel.ORT_DISABLE_ALL,
              'basic': ort.GraphOptimizationLevel.ORT_ENABLE_BASIC,
              'extended': ort.GraphOptimizationLevel.ORT_ENABLE_EXTENDED,
              'all': ort.GraphOptimizationLevel.ORT_ENABLE_ALL}
    if threads < 1:
        raise ValueError('threads must be positive')
    opts.graph_optimization_level = levels[optimization]
    if save_graph is not None:
        opts.optimized_model_filepath = str(save_graph)
    # Model and session constructed ONCE, reused for every speech interval.
    return ort.InferenceSession(str(path), sess_options=opts, providers=['CPUExecutionProvider'])


def embedding_input(session):
    inputs = session.get_inputs()
    outputs = session.get_outputs()
    if (len(inputs) != 1 or inputs[0].type != 'tensor(float)'
            or len(inputs[0].shape) != 3 or inputs[0].shape[-1] != 80
            or len(outputs) != 1 or outputs[0].type != 'tensor(float)'
            or len(outputs[0].shape) != 2 or outputs[0].shape[-1] != 192):
        raise ValueError('Expected float32 [batch, frames, 80] and [batch, 192]')
    return inputs[0].name


def embed_turns(audio, turns, session):
    name = embedding_input(session)
    result = []
    for turn in turns:
        embedding = None
        if turn.end - turn.start >= RATE // 4 and not turn.overlap:
            feature = extract_fbank(audio[turn.start:turn.end])
            prediction = session.run(None, {name: feature[None, :, :]})[0]
            if prediction.shape != (1, 192):
                raise ValueError(f'Unexpected embedding shape: {prediction.shape}')
            embedding = prediction[0]
        result.append(Segment(turn.start / RATE, turn.end / RATE, embedding, turn.overlap))
    return result


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--audio', type=Path, required=True, help='WAV/FLAC readable by soundfile')
    source = p.add_mutually_exclusive_group(required=True)
    source.add_argument('--segments', type=Path, help='CSV: start,end[,overlap] (seconds)')
    source.add_argument('--pyannote', type=Path, help='Local Pyannote segmentation-3.0 ONNX')
    p.add_argument('--model', type=Path, default=Path('models/speaker_embedding_v2_basic.onnx'))
    p.add_argument('--output', type=Path, default=Path('reports/diarization.csv'))
    p.add_argument('--threads', type=int, default=2)
    p.add_argument('--ort-opt', choices=('off', 'basic', 'extended', 'all'), default='extended')
    p.add_argument('--linkage', choices=('complete', 'average'), default='complete')
    p.add_argument('--max-window', type=float, default=4.0, help='Maximum clean inference window, seconds')
    p.add_argument('--seed-seconds', type=float, default=1.2)
    p.add_argument('--distance', type=float, default=0.32)
    p.add_argument('--min-similarity', type=float, default=0.45)
    p.add_argument('--margin', type=float, default=0.05)
    p.add_argument('--max-speakers', type=int, default=None, help='Only set if true speaker count is known')
    args = p.parse_args()
    start_time = time.perf_counter()
    audio = read_audio(args.audio)
    if args.segments is not None:
        with args.segments.open(encoding='utf-8-sig', newline='') as f:
            rows = list(csv.DictReader(f))
    else:
        from pyannote_segments import segment_audio
        rows = segment_audio(audio, make_session(args.pyannote, args.threads))
    turns = list(prepare_turns(rows, len(audio), args.max_window))
    session = make_session(args.model, args.threads, args.ort_opt)
    segments = embed_turns(audio, turns, session)
    labels, explanations = global_cluster(
        segments, args.distance, args.seed_seconds, args.min_similarity, args.margin,
        args.max_speakers, args.linkage)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open('w', newline='', encoding='utf-8-sig') as f:
        writer = csv.DictWriter(f, fieldnames=['start', 'end', 'speaker', 'reason', 'similarity'])
        writer.writeheader()
        for turn, label, explanation in zip(segments, labels, explanations):
            writer.writerow({'start': turn.start, 'end': turn.end,
                'speaker': f'Speaker {label}' if label else 'UNKNOWN',
                'reason': explanation['reason'], 'similarity': explanation['confidence']})
    print(json.dumps({'segments': len(turns), 'embeddings': sum(s.embedding is not None for s in segments),
                      'embedded_seconds': round(sum(s.duration for s in segments if s.embedding is not None), 4),
                      'speakers': len(set(labels)-{0}),
                      'unknown': labels.count(0), 'total_seconds': round(time.perf_counter()-start_time, 3),
                      'output': str(args.output)}, ensure_ascii=False, indent=2))

if __name__ == '__main__':
    main()
