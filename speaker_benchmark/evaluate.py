"""Calibrate with validation only, then score frozen variants on a separate split."""
import argparse
import csv
from itertools import product
import json
import os
from pathlib import Path
import subprocess
import sys
import time

import numpy as np

from clustering import global_cluster
from features import extract_fbank
from intervals import RATE, prepare_turns
from model_benchmark import INT8, MODEL, PORTABLE, REPORT, write_csv
from pipeline import embed_turns, make_session, read_audio
from score import read, score

CASES = Path('runs/controlled')
CONFIG = REPORT / 'validation_selection.json'


def load_case(case):
    audio = read_audio(case / 'audio.wav')
    with (case / 'segments.csv').open(newline='') as file:
        turns = list(prepare_turns(csv.DictReader(file), len(audio)))
    return audio, turns, read(case / 'reference.csv')


def cached_inputs(split):
    tensors = []
    for case in sorted((CASES / split).iterdir()):
        audio, turns, _ = load_case(case)
        for turn in turns:
            if not turn.overlap and turn.end - turn.start >= RATE // 4:
                tensors.append(extract_fbank(audio[turn.start:turn.end])[None, :, :])
    np.savez(f'runs/{split}_features.npz', *tensors)
    if split == 'test':
        audio = read_audio(CASES / 'test/clean/audio.wav')
        lengths = (.35, 1.0, 2.0, 2.75)
        benchmark = [extract_fbank(audio[:round(length * RATE)])[None, :, :] for length in lengths]
        np.savez('runs/benchmark_features.npz', *benchmark)


def classify(segments, parameters):
    labels, _ = global_cluster(segments, **parameters)
    return [(s.start, s.end, f'Speaker {label}' if label else 'UNKNOWN')
            for s, label in zip(segments, labels)]


def validation():
    results = []
    for case in sorted((CASES / 'validation').iterdir()):
        audio, turns, reference = load_case(case)
        session = make_session(MODEL, 2)
        segments = embed_turns(audio, turns, session)
        results.append((segments, reference))
    records = []
    for method, distance, similarity, margin in product(
            ('complete', 'average'), (.26, .32, .38, .44, .50), (.45, .55, .68), (.05, .10)):
        parameters = dict(linkage_method=method, max_cosine_distance=distance,
                          short_assign_similarity=similarity, margin=margin)
        metrics = [score(reference, classify(segments, parameters)) for segments, reference in results]
        totals = {key: sum(row[key] for row in metrics) for key in (
            'miss_seconds', 'false_alarm_seconds', 'confusion_seconds', 'reference_speaker_seconds',
            'merge_pairs', 'split_pairs', 'short_correct', 'return_correct')}
        der = sum(totals[key] for key in ('miss_seconds', 'false_alarm_seconds', 'confusion_seconds'))
        records.append(dict(**parameters, **totals, der=der / totals['reference_speaker_seconds']))
    write_csv(REPORT / 'validation_grid.csv', records)
    # Within 0.01 pp of best DER, use pair errors and a prior favoring original thresholds.
    best = min(records, key=lambda row: (round(row['der'], 4), row['merge_pairs'], row['split_pairs'],
                                        -row['short_correct'], -row['return_correct'],
                                        abs(row['max_cosine_distance'] - .32),
                                        abs(row['short_assign_similarity'] - .68), row['margin'],
                                        row['linkage_method'] != 'complete'))
    parameters = {key: best[key] for key in ('linkage_method', 'max_cosine_distance',
                                             'short_assign_similarity', 'margin')}
    CONFIG.write_text(json.dumps(parameters, indent=2), encoding='utf-8')
    print(json.dumps(best, indent=2))


def test(baseline):
    parameters = json.loads(CONFIG.read_text())
    records = []
    variants = [('new_original', MODEL, 'extended'), ('new_fp32_basic', PORTABLE, 'extended'),
                ('new_original_all', MODEL, 'all'), ('new_int8_qdq', INT8, 'extended')]
    for case in sorted((CASES / 'test').iterdir()):
        reference = read(case / 'reference.csv')

        def run(entrypoint, prediction, options=()):
            start = time.perf_counter()
            completed = subprocess.run(
                [*entrypoint, '--audio', str(case / 'audio.wav'),
                 '--segments', str(case / 'segments.csv'), '--output', str(prediction), *options],
                check=True, capture_output=True, text=True,
                env={**os.environ, 'OMP_NUM_THREADS': '1', 'MKL_NUM_THREADS': '1'})
            return json.loads(completed.stdout), time.perf_counter() - start

        if baseline:
            prediction = case / 'baseline.csv'
            entrypoint = [sys.executable, 'runs/baseline/pipeline.py']
            if baseline == 'shared-fbank':
                entrypoint = [sys.executable, '-c',
                              "import features, runpy, sys; "
                              "sys.path.insert(0, 'runs/baseline'); "
                              "runpy.run_path('runs/baseline/pipeline.py', run_name='__main__')"]
            summary, elapsed = run(entrypoint, prediction)
            records.append(dict(case=case.name, variant=f'archive_{baseline}', segments=summary['segments'],
                                wall_seconds=elapsed,
                                **score(reference, read(prediction))))
        for label, path, level in variants:
            if not path.is_file():
                continue
            prediction = case / f'{label}.csv'
            options = ('--model', str(path), '--ort-opt', level, '--linkage', parameters['linkage_method'],
                       '--distance', str(parameters['max_cosine_distance']),
                       '--min-similarity', str(parameters['short_assign_similarity']),
                       '--margin', str(parameters['margin']))
            summary, elapsed = run([sys.executable, 'pipeline.py'], prediction, options)
            records.append(dict(case=case.name, variant=label, segments=summary['segments'],
                                wall_seconds=elapsed, **score(reference, read(prediction))))
    write_csv(REPORT / 'diarization_test.csv', records)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('phase', choices=('validation', 'test'))
    parser.add_argument('--baseline', nargs='?', const='original',
                        choices=('original', 'shared-fbank'))
    args = parser.parse_args()
    if args.phase == 'validation':
        cached_inputs('validation')
        validation()
    else:
        cached_inputs('test')
        test(args.baseline)


if __name__ == '__main__':
    main()
