"""Change only the embedding model on frozen production windows (no reference-assisted inference)."""
import argparse
import csv
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import numpy as np
import onnxruntime as ort


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--audit', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--embedding', type=Path, required=True)
    args = p.parse_args()
    root = Path(__file__).resolve().parents[2]
    java = 'C:/Users/Юлия Кротова/.jdks/jbr-21.0.11/bin/java.exe'
    # The frozen audit contains the actual production Java .52 clusterer/feature driver.
    classpath = str(args.audit / 'java_classes')
    case = json.loads((args.audit / 'cases.json').read_text(encoding='utf-8'))[0]
    name = case['name']
    folder = args.audit / name
    windows = case['configs'][0]['windows']
    with (folder / 'baseline_2s_hop1s/input_windows.csv').open(encoding='utf-8-sig') as f:
        samples = list(csv.DictReader(f))
    args.output.mkdir(parents=True, exist_ok=True)
    options = ort.SessionOptions(); options.intra_op_num_threads = 2; options.inter_op_num_threads = 1
    session = ort.InferenceSession(str(args.embedding), sess_options=options, providers=['CPUExecutionProvider'])
    vectors = []
    started = time.perf_counter()
    with tempfile.TemporaryDirectory(dir=args.output, prefix='features-') as temporary:
        temporary = Path(temporary)
        with (temporary / 'turns.csv').open('w', newline='') as f:
            writer = csv.writer(f); writer.writerow(['start', 'end', 'overlap'])
            writer.writerows((r['start'], r['end'], 0) for r in samples)
        subprocess.run([java, '-cp', classpath, 'com.bimvo.app.SpeakerBenchmarkDriver', 'features',
                        str(folder / 'audio.pcm'), str(temporary / 'turns.csv'), str(temporary / 'features')], check=True)
        with (temporary / 'features/features.csv').open() as f:
            features = list(csv.DictReader(f))
        for i, (sample, feature) in enumerate(zip(samples, features)):
            frames = int(feature['frames']); vector = None
            if frames:
                x = np.fromfile(temporary / 'features' / feature['file'], dtype='<f4').reshape(1, frames, 80)
                vector = session.run(None, {session.get_inputs()[0].name: x})[0][0]
            vectors.append(','.join([sample['start'], sample['end']] + ([] if vector is None else [str(float(v)) for v in vector])))
            if i % 100 == 0: print(f'embeddings {i}/{len(samples)}', flush=True)
    (args.output / 'embedding_input.csv').write_text('\n'.join(vectors), encoding='utf-8')
    subprocess.run([java, '-cp', classpath, 'com.bimvo.app.SpeakerBenchmarkDriver', 'cluster',
                    str(args.output / 'embedding_input.csv'), str(args.output / 'labels.txt')], check=True)
    labels = list(map(int, (args.output / 'labels.txt').read_text().split()))
    assert len(labels) == len(windows)
    prediction = [(w['start'], w['end'], f'speaker_{i}' if i else 'UNKNOWN') for w, i in zip(windows, labels)]
    with (args.output / 'prediction.csv').open('w', newline='') as f:
        writer = csv.writer(f); writer.writerow(['start', 'end', 'speaker']); writer.writerows(prediction)
    sys.path.insert(0, str(root / 'speaker_benchmark'))
    from score import score, read
    report = {'meeting': name, 'embedding': str(args.embedding), 'distance_threshold': .52,
              'seconds': time.perf_counter() - started,
              'protocol': 'Only embedding weights changed; production fbank, VAD windows, complete linkage .52 and timings frozen.',
              'metrics': {kind: score(read(args.audit / f'reference_{kind}.csv'), prediction) for kind in ['segments', 'words']}}
    (args.output / 'report.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    print(json.dumps(report), flush=True)


if __name__ == '__main__':
    main()
