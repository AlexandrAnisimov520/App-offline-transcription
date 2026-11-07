"""Score a frozen AMI meeting audit against official AMI v1.6.2 manual annotations.

The optional old labels must be generated on exactly the same windows and embeddings.
This isolates clustering, rather than comparing two complete Android pipelines.
"""
import argparse
import csv
import hashlib
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--audit', type=Path, required=True)
    parser.add_argument('--meeting', default='ES2002a')
    parser.add_argument('--annotations', type=Path, required=True)
    parser.add_argument('--old-labels', type=Path)
    args = parser.parse_args()
    case = json.loads((args.audit / 'cases.json').read_text(encoding='utf-8'))[0]
    if case['name'] != args.meeting:
        raise ValueError('Audit meeting does not match the requested reference')
    duration = case['duration_seconds']
    sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'speaker_benchmark'))
    from score import score
    references = {}
    with zipfile.ZipFile(args.annotations) as archive:
        for kind in ['segments', 'words']:
            rows = []
            for speaker in 'ABCD':
                data = archive.read(f'{kind}/{args.meeting}.{speaker}.{kind}.xml')
                (args.audit / f'{args.meeting}.{speaker}.{kind}.xml').write_bytes(data)
                local = []
                for element in ET.fromstring(data):
                    if kind == 'segments':
                        a, b = element.get('transcriber_start'), element.get('transcriber_end')
                    else:
                        if element.tag != 'w' or element.get('punc') == 'true':
                            continue
                        a, b = element.get('starttime'), element.get('endtime')
                    if a is None or b is None:
                        continue
                    a, b = max(0, float(a)), min(duration, float(b))
                    if b > a:
                        local.append((a, b))
                merged = []
                for a, b in sorted(local):
                    if merged and a <= merged[-1][1]:
                        merged[-1][1] = max(merged[-1][1], b)
                    else:
                        merged.append([a, b])
                rows.extend((a, b, 'AMI_' + speaker) for a, b in merged)
            references[kind] = sorted(rows)
            with (args.audit / f'reference_{kind}.csv').open('w', encoding='utf-8', newline='') as stream:
                writer = csv.writer(stream)
                writer.writerow(['start', 'end', 'speaker'])
                writer.writerows(references[kind])
    windows = case['configs'][0]['windows']
    configurations = [('complete_0.52', [w['speaker'] if w['speaker'] != 'speaker_unknown' else 'UNKNOWN' for w in windows])]
    if args.old_labels:
        old = [int(x) for x in args.old_labels.read_text().split()]
        if len(old) != len(windows):
            raise ValueError('Old labels do not match the frozen window count')
        configurations.insert(0, ('complete_0.32', [f'speaker_{i}' if i else 'UNKNOWN' for i in old]))
    results = []
    for name, labels in configurations:
        prediction = [(w['start'], w['end'], label) for w, label in zip(windows, labels)]
        for kind, reference in references.items():
            result = {'configuration': name, 'reference': kind, **score(reference, prediction)}
            results.append(result)
            print(name, kind, 'DER:', f"{result['der']:.2%}", 'speakers:', result['predicted_speakers'])
    with args.annotations.open('rb') as stream:
        annotation_hash = hashlib.file_digest(stream, 'sha256').hexdigest()
    output = {'meeting': args.meeting, 'duration_seconds': duration, 'windows': len(windows),
              'annotation_sha256': annotation_hash,
              'comparison': 'Same windows/embeddings/new VAD frontend for both thresholds; clustering-only comparison. '
                            'Threshold .52 frozen before AMI. No reference speaker counts used for predictions.',
              'protocol': 'Zero collar; include overlap; entire recording; global optimal mapping per recording. '
                          'Primary reference: manual transcriber segments. Secondary: lexical word intervals, '
                          'union overlapping/touching intervals without gap filling. Not a standard leaderboard score.',
              'vad_gain': case['audio_quality'].get('vad_input_gain', 1), 'results': results}
    (args.audit / 'DER_RESULTS.json').write_text(json.dumps(output, indent=2), encoding='utf-8')


if __name__ == '__main__':
    main()
