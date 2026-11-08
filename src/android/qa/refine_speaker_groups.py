"""Reference-free group prototypes with overlap constraints; calibrate before validation."""
import csv
import argparse
import hashlib
import ctypes
import json
import os
from pathlib import Path
import sys
import wave
import zipfile
import xml.etree.ElementTree as ET
import numpy as np

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'diarization_v4/refinement'
RUNTIME = Path('D:/BIMVO-diarization-runtime-v2')
sys.path.insert(0, str(ROOT / 'speaker_benchmark'))
from score import score, read
from diarization_diagnostics import diagnostics


def reference(meeting, duration):
    result = []
    with zipfile.ZipFile('D:/BIMVO-AMI/ami_public_manual_1.6.2.zip') as archive:
        for speaker in 'ABCD':
            intervals = []
            for e in ET.fromstring(archive.read(f'segments/{meeting}.{speaker}.segments.xml')):
                a, b = e.get('transcriber_start'), e.get('transcriber_end')
                if a is not None and b is not None:
                    a, b = max(0, float(a)), min(duration, float(b))
                    if b > a: intervals.append((a, b))
            merged = []
            for a, b in sorted(intervals):
                if merged and a <= merged[-1][1]: merged[-1][1] = max(b, merged[-1][1])
                else: merged.append([a, b])
            result.extend((a, b, 'AMI_' + speaker) for a, b in merged)
    return sorted(result)


def prepare(meeting, prediction, duration, extractor):
    rows = read(prediction)
    labels = sorted({s for a, b, s in rows})
    folder = OUT / meeting
    folder.mkdir(parents=True, exist_ok=True)
    cache = folder / 'prototypes.npz'
    provenance = {'prediction_sha256': hashlib.sha256(Path(prediction).read_bytes()).hexdigest(),
                  'duration': duration, 'exclusive_min_seconds': .4, 'cap_seconds': 20,
                  'model_sha256': hashlib.sha256((ROOT/'diarization_models/eres2net_voxceleb.onnx').read_bytes()).hexdigest()}
    metadata = folder / 'prototype_provenance.json'
    if cache.exists():
        data = np.load(cache)
        if not metadata.exists() or json.loads(metadata.read_text()) != provenance:
            raise ValueError('Cache provenance mismatch; use a new experiment directory')
        if list(data['labels']) != labels: raise ValueError('Cache labels mismatch')
        return rows, labels, data['vectors'], data['weights']
    with wave.open(f'D:/BIMVO-AMI/{meeting}.wav') as f:
        audio = np.frombuffer(f.readframes(round(duration * 16000)), dtype='<i2').astype(np.float32) / 32768
    vectors, weights = [], []
    for label in labels:
        clean = []
        for a, b, s in rows:
            if s != label: continue
            pieces = [(a, b)]
            for c, d, other in rows:
                if other == label or d <= a or c >= b: continue
                pieces = [(x, min(y, c)) for x, y in pieces if x < c] + [(max(x, d), y) for x, y in pieces if y > d]
            clean.extend((a, b) for a, b in pieces if b - a >= .4)
        selected, total = [], 0
        for a, b in sorted(clean, key=lambda p: p[1]-p[0], reverse=True):
            n = min(round((b-a)*16000), 20*16000-total)
            if n <= 0: break
            selected.append(audio[round(a*16000):round(a*16000)+n]); total += n
        weights.append(total / 16000)
        if total < 6400:
            vectors.append(np.zeros(extractor.dim)); continue
        stream = extractor.create_stream()
        stream.accept_waveform(16000, np.concatenate(selected))
        stream.input_finished()
        if not extractor.is_ready(stream):
            vectors.append(np.zeros(extractor.dim)); continue
        v = np.asarray(extractor.compute(stream))
        if not np.all(np.isfinite(v)): v = np.zeros(extractor.dim)
        vectors.append(v / max(np.linalg.norm(v), 1e-12))
    vectors, weights = np.asarray(vectors), np.asarray(weights)
    np.savez(cache, labels=labels, vectors=vectors, weights=weights)
    metadata.write_text(json.dumps(provenance, indent=2), encoding='utf-8')
    return rows, labels, vectors, weights


def refine(rows, labels, vectors, weights, threshold):
    # Reject any proposed merge with an original pair of intervals overlapping >=100ms.
    index = {s: i for i, s in enumerate(labels)}
    conflicts = set()
    for a, b, s in rows:
        for c, d, t in rows:
            if s != t and min(b,d)-max(a,c) >= .1:
                conflicts.add(frozenset((index[s], index[t])))
    groups = [{i} for i in range(len(labels))]
    def centroid(g):
        v = sum((vectors[i] * weights[i] for i in g), np.zeros(vectors.shape[1]))
        return v / max(np.linalg.norm(v), 1e-12)
    while True:
        best = None
        for i, g in enumerate(groups):
            for j in range(i+1, len(groups)):
                h = groups[j]
                if any(frozenset((a,b)) in conflicts for a in g for b in h): continue
                similarity = float(centroid(g) @ centroid(h))
                if similarity >= threshold and (best is None or similarity > best[0]): best = similarity, i, j
        if best is None: break
        _, i, j = best
        groups[i] |= groups.pop(j)
    mapping = {labels[k]: f'speaker_{i+1}' for i,g in enumerate(groups) for k in g}
    return [(a,b,mapping[s]) for a,b,s in rows]


def main():
    global OUT
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--calibration-seconds', type=int, default=300)
    args = p.parse_args()
    duration_cal = args.calibration_seconds
    if duration_cal != 300: OUT = ROOT / f'diarization_v4/refinement{duration_cal}'
    OUT.mkdir(parents=True, exist_ok=True)
    thresholds = [.35, .45, .55, .65, .75]
    plan = {'thresholds': thresholds, 'calibration': f'ES2002b first{duration_cal}s', 'selection': 'minimum segment DER; ties prefer fewer merges',
            'validation': ['ES2002a full', 'TS3004a first300s'], 'no_oracle_count': True,
            'method': 'exclusive-speech group prototypes, duration-weighted centroid merging, overlap cannot-link >=.1s',
            'model': 'same ERes2Net VoxCeleb; no training; prototypes cap20s per group',
            'limitations': 'AMI only; ES2002a/b share participants; short calibration; validation recordings previously inspected'}
    (OUT/'PLAN.json').write_text(json.dumps(plan, indent=2), encoding='utf-8')
    sys.path.insert(0, str(RUNTIME))
    handle = os.add_dll_directory(str(RUNTIME/'Scripts'))
    ctypes.WinDLL(str(RUNTIME/'Scripts/onnxruntime.dll'))
    import sherpa_onnx as s
    extractor = s.SpeakerEmbeddingExtractor(s.SpeakerEmbeddingExtractorConfig(model=str(ROOT/'diarization_models/eres2net_voxceleb.onnx'), num_threads=2))
    source_cal = 'calibration_0.85' if duration_cal == 300 else f'calibration_{duration_cal}_0.85'
    rows, labels, vectors, weights = prepare('ES2002b', ROOT/f'diarization_v4/{source_cal}/prediction.csv', duration_cal, extractor)
    ref = reference('ES2002b', duration_cal)
    results = []
    for t in thresholds:
        pred = refine(rows, labels, vectors, weights, t)
        result = {'threshold': t, 'der': score(ref,pred)['der'], 'speakers': len({s for a,b,s in pred})}
        results.append(result)
        print('CALIBRATION', result, flush=True)
    best = min(results, key=lambda r: (r['der'], -r['threshold']))
    (OUT/'FROZEN_SELECTION.json').write_text(json.dumps({'plan':plan,'results':results,'selected':best},indent=2),encoding='utf-8')
    summary = []
    for meeting, duration, source in [('ES2002a',1272.64,'ES2002a'),('TS3004a',300,'TS3004a_first300s')]:
        rows, labels, vectors, weights = prepare(meeting,ROOT/f'diarization_v3/{source}/prediction.csv',duration,extractor)
        pred = refine(rows,labels,vectors,weights,best['threshold'])
        ref = reference(meeting,duration)
        report = {'meeting':meeting,'threshold':best['threshold'],'baseline':score(ref,rows),'refined':score(ref,pred),'diagnostics':diagnostics(ref,pred)}
        folder = OUT/meeting
        with (folder/'prediction.csv').open('w',newline='',encoding='utf-8') as f:
            w=csv.writer(f); w.writerow(['start','end','speaker']); w.writerows(pred)
        (folder/'report.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
        summary.append(report)
        print('VALIDATION',meeting,report['refined']['der'],report['refined']['predicted_speakers'],flush=True)
    (OUT/'SUMMARY.json').write_text(json.dumps(summary,indent=2),encoding='utf-8')


if __name__ == '__main__': main()
