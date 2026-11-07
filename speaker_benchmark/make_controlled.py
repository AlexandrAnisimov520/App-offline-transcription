"""Build reproducible speaker-disjoint controlled recordings from Mini LibriSpeech."""
import argparse
from collections import defaultdict
import csv
import io
import json
from pathlib import Path
import tarfile

import numpy as np
import soundfile as sf

from intervals import RATE

SOURCE = 'https://openslr.trmal.net/resources/31/dev-clean-2.tar.gz'


def collect(archive):
    samples = defaultdict(list)
    with tarfile.open(archive, 'r|gz') as source:
        for member in source:
            if not member.name.endswith('.flac'):
                continue
            speaker = Path(member.name).name.split('-')[0]
            if len(samples[speaker]) >= 9:
                continue
            data, rate = sf.read(io.BytesIO(source.extractfile(member).read()), dtype='float32')
            if rate != RATE or data.ndim != 1:
                raise ValueError(f'Unexpected audio format: {member.name}')
            if len(data) >= 3.25 * RATE:
                samples[speaker].append((member.name, data))
            if sum(len(items) == 9 for items in samples.values()) == 8:
                break
    speakers = [key for key, value in samples.items() if len(value) == 9]
    if len(speakers) < 8:
        raise ValueError('Expected eight speakers with nine suitable utterances each')
    return {key: samples[key] for key in speakers}


def save_csv(path, fields, rows):
    with path.open('w', newline='', encoding='utf-8') as handle:
        writer = csv.writer(handle)
        writer.writerow(fields)
        writer.writerows(rows)


def make_case(target, case, ids, utterances, stress, offset):
    # Each event is (speaker slot, start, duration). Long gaps are intentional.
    plan = [(0, 0, 2.75), (1, 3, 2.5), (2, 6, 2.75),
            (0, 9, .35), (1, 9.35, 2.6), (2, 12.2, .55),
            (0, 13, 2.8), (2, 16.1, 2.75), (1, 19.1, .8),
            (2, 20.1, 2.55), (0, 56, 2.8), (1, 59.1, 2.65),
            (2, 62, 2.5)]
    if stress:
        plan[4] = (1, 9.18, 2.6)
        plan[7] = (2, 15.45, 2.75)
    track = np.zeros(65 * RATE, dtype=np.float32)
    reference, intervals, provenance = [], [], []
    index = defaultdict(lambda: offset)
    for slot, start, duration in plan:
        speaker = ids[slot]
        a, n = round(start * RATE), round(duration * RATE)
        name, original = utterances[speaker][index[speaker]]
        index[speaker] += 1
        center = (len(original) - n) // 2
        wave = original[center:center + n].copy()
        fade = min(80, n // 2)
        wave[:fade] *= np.linspace(0, 1, fade)
        wave[-fade:] *= np.linspace(1, 0, fade)
        track[a:a + n] += wave * 0.45
        end = (a + n) / RATE
        reference.append((start, end, speaker))
        intervals.append((start, end, 0))
        provenance.append(dict(source_file=name, first_sample=center, samples=n))
    if stress:
        intervals.extend([(0, 2.75, 0), (.07, 2.81, 0), (15.52, 18.27, 0),
                          (9.18, 9.35, 1), (15.45, 15.8, 1)])
    folder = target / case
    folder.mkdir(parents=True, exist_ok=True)
    sf.write(folder / 'audio.wav', track, RATE)
    save_csv(folder / 'reference.csv', ('start', 'end', 'speaker'), reference)
    save_csv(folder / 'segments.csv', ('start', 'end', 'overlap'), sorted(intervals))
    (folder / 'sources.json').write_text(json.dumps(provenance, indent=2), encoding='utf-8')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('archive', type=Path)
    parser.add_argument('--output', type=Path, default=Path('runs/controlled'))
    args = parser.parse_args()
    utterances = collect(args.archive)
    speakers = list(utterances)
    for split, ids in (('validation', speakers[:4]), ('test', speakers[4:])):
        make_case(args.output, f'{split}/clean', ids[:3], utterances, False, 0)
        make_case(args.output, f'{split}/overlap', ids[1:], utterances, True, 4)
    (args.output / 'manifest.json').write_text(json.dumps(dict(
        source=SOURCE, license='CC BY 4.0', validation=speakers[:4], test=speakers[4:]
    ), indent=2), encoding='utf-8')


if __name__ == '__main__':
    main()
