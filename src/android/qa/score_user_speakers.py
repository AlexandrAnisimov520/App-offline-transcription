"""Score an independently verified REVIEW.html CSV against frozen audit predictions."""
import argparse
import csv
import json
import math
from pathlib import Path
import sys


def load_reference(path, cases):
    grouped = {c['name']: [] for c in cases}
    by_name = {c['name'] + '.m4a': c for c in cases}
    with Path(path).open(encoding='utf-8-sig', newline='') as stream:
        for row in csv.DictReader(stream):
            if row['file'] not in by_name:
                raise ValueError('Unknown audio: ' + row['file'])
            case = by_name[row['file']]
            if row['source_sha256'] != case['source_sha256']:
                raise ValueError('Audio SHA256 mismatch: ' + row['file'])
            if row['verified'].lower() != 'true':
                raise ValueError('Every reference interval must be independently verified')
            a, b = float(row['start']), float(row['end'])
            if not (math.isfinite(a) and math.isfinite(b) and 0 <= a < b <= case['duration_seconds'] + .02):
                raise ValueError('Invalid reference timing: ' + repr(row))
            allowed = {f'speaker_{i+1}' for i in range(case['expected_speakers'])}
            if row['speaker'] not in allowed:
                raise ValueError('Invalid reference speaker: ' + repr(row))
            grouped[case['name']].append((a, b, row['speaker']))
    for case in cases:
        rows = grouped[case['name']]
        if len({r[2] for r in rows}) != case['expected_speakers']:
            raise ValueError('Missing verified speakers/recording: ' + case['name'])
        if len(rows) != len(set(rows)):
            raise ValueError('Duplicate reference intervals: ' + case['name'])
        for speaker in {r[2] for r in rows}:
            previous_end = 0
            for a, b, _ in sorted(r for r in rows if r[2] == speaker):
                if a < previous_end:
                    raise ValueError('Overlapping intervals for same speaker: ' + case['name'])
                previous_end = b
        rows.sort()
    return grouped


def evaluate(audit, reference):
    cases = json.loads((audit / 'cases.json').read_text(encoding='utf-8'))
    refs = load_reference(reference, cases)
    sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'speaker_benchmark'))
    from score import score
    rows = []
    for case in cases:
        for config in case['configs']:
            prediction = [(w['start'], w['end'], 'UNKNOWN' if w['speaker'] == 'speaker_unknown' else w['speaker'])
                          for w in config['windows']]
            rows.append({'file': case['name'], 'split': case['split'],
                         'configuration': config['summary']['configuration'],
                         **score(refs[case['name']], prediction)})
    aggregates = []
    for split, configuration in sorted({(r['split'], r['configuration']) for r in rows}):
        subset = [r for r in rows if (r['split'], r['configuration']) == (split, configuration)]
        total = sum(r['reference_speaker_seconds'] for r in subset)
        errors = sum(r['miss_seconds'] + r['false_alarm_seconds'] + r['confusion_seconds'] for r in subset)
        aggregates.append({'split': split, 'configuration': configuration, 'recordings': len(subset),
                           'der': errors / total if total else None, 'reference_speaker_seconds': total})
    return {'protocol': 'Zero collar; include overlap; one global optimal speaker mapping per recording. '
                        'Unknown predictions abstain. Human reference completeness still requires review.',
            'reference_file': str(reference.resolve()), 'aggregate': aggregates, 'files': rows}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--audit', type=Path, required=True)
    parser.add_argument('--reference', type=Path, required=True)
    args = parser.parse_args()
    result = evaluate(args.audit, args.reference)
    output = args.audit / 'verified_scores.json'
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(result['aggregate'], ensure_ascii=False, indent=2))
    print(output)


if __name__ == '__main__':
    main()
