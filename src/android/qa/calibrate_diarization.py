"""Bounded calibration on ES2002b; freeze selection before evaluating other audio."""
import json
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'diarization_v4'
THRESHOLDS = [.85, .95, 1.05, 1.15]


def run(meeting, seconds, threshold, folder):
    subprocess.run([sys.executable, str(Path(__file__).with_name('eval_sherpa_diarization.py')),
                    '--audio', f'D:/BIMVO-AMI/{meeting}.wav', '--meeting', meeting,
                    '--seconds', str(seconds), '--threshold', str(threshold),
                    '--output', str(folder)], check=True)
    return json.loads((folder / 'report.json').read_text(encoding='utf-8'))


def main():
    OUT.mkdir(exist_ok=True)
    plan = {'calibration': 'ES2002b first 300 seconds', 'thresholds': THRESHOLDS,
            'selection': 'lowest segment DER, ties prefer lower threshold',
            'validation': ['ES2002a full', 'TS3004a first 300 seconds'],
            'speaker_count': 'automatic, never supplied',
            'limitation': 'same corpus; ES2002a and ES2002b share meeting participants; validation is not unseen-domain testing'}
    (OUT / 'PLAN.json').write_text(json.dumps(plan, indent=2), encoding='utf-8')
    rows = []
    for threshold in THRESHOLDS:
        report = run('ES2002b', 300, threshold, OUT / f'calibration_{threshold:.2f}')
        rows.append({'threshold': threshold, 'der': report['metrics']['segments']['der'],
                     'speakers': report['speakers']})
        print('CALIBRATION', rows[-1], flush=True)
    best = min(rows, key=lambda row: (row['der'], row['threshold']))
    (OUT / 'FROZEN_SELECTION.json').write_text(json.dumps({'plan': plan, 'results': rows, 'selected': best}, indent=2), encoding='utf-8')
    if best['threshold'] == .85:
        print('UNCHANGED: baseline selected; existing v3 validation remains applicable; skipping duplicate inference.', flush=True)
        return
    for meeting, seconds in [('ES2002a', 1272.64), ('TS3004a', 300)]:
        report = run(meeting, seconds, best['threshold'], OUT / f'validation_{meeting}')
        print('VALIDATION', meeting, report['metrics']['segments']['der'], report['speakers'], flush=True)


if __name__ == '__main__':
    main()
