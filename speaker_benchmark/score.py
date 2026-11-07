"""Time-based DER and turn-level diagnostics using global label mapping."""
import argparse
from collections import Counter, defaultdict
import csv
import json
import math

import numpy as np
from scipy.optimize import linear_sum_assignment


def read(path):
    with open(path, encoding='utf-8-sig', newline='') as f:
        rows = []
        for row in csv.DictReader(f):
            a, b, speaker = float(row['start']), float(row['end']), row['speaker'].strip()
            if not (math.isfinite(a) and math.isfinite(b) and 0 <= a < b and speaker):
                raise ValueError(f'Invalid interval in {path}: {row}')
            rows.append((a, b, speaker))
        return sorted(set(rows))


def score(reference, prediction, short=1.2, return_gap=30.0):
    """Score all speech including overlaps, with zero collar. UNKNOWN abstains."""
    events = defaultdict(list)
    for side, rows in enumerate((reference, prediction)):
        for i, (a, b, name) in enumerate(rows):
            if name == 'UNKNOWN':
                if side == 0:
                    raise ValueError('Reference speaker labels must be known')
                continue
            events[a].append((side, i, name, 1))
            events[b].append((side, i, name, -1))
    active = [Counter(), Counter()]
    active_turns = set()
    overlap = Counter()
    per_turn = [Counter() for _ in reference]
    ref_time = miss = false_alarm = possible_correct = 0.0
    previous = 0.0
    for t, changes in sorted(events.items()):
        duration = t - previous
        ref, hyp = active
        ref_time += duration * len(ref)
        miss += duration * max(0, len(ref) - len(hyp))
        false_alarm += duration * max(0, len(hyp) - len(ref))
        possible_correct += duration * min(len(ref), len(hyp))
        for name in ref:
            for predicted in hyp:
                overlap[name, predicted] += duration
        for i in active_turns:
            for predicted in hyp:
                per_turn[i][predicted] += duration
        for side, i, name, delta in changes:
            active[side][name] += delta
            if not active[side][name]:
                del active[side][name]
            if side == 0:
                if delta > 0:
                    active_turns.add(i)
                else:
                    active_turns.remove(i)
        previous = t
    actual = sorted({name for _, _, name in reference})
    guessed = sorted({name for _, _, name in prediction if name != 'UNKNOWN'})
    matrix = np.array([[overlap[a, b] for b in guessed] for a in actual])
    mapping = {}
    correct = 0.0
    if actual and guessed:
        ri, ci = linear_sum_assignment(matrix, maximize=True)
        for i, j in zip(ri, ci):
            if matrix[i, j] > 0:
                mapping[guessed[j]] = actual[i]
                correct += matrix[i, j]
    confusion = max(0.0, possible_correct - correct)

    short_total = short_correct = return_total = return_correct = 0
    last_end = {}
    pairs = Counter()
    for (a, b, name), counts in zip(reference, per_turn):
        best = None
        if counts:
            (candidate, coverage), *rest = counts.most_common(2)
            if coverage > (b - a) / 2 and (not rest or coverage > rest[0][1]):
                best = candidate
                pairs[name, best] += 1
        matched = mapping.get(best) == name
        if b - a < short - 1e-9:
            short_total += 1
            short_correct += matched
        if name in last_end and a - last_end[name] >= return_gap:
            return_total += 1
            return_correct += matched
        last_end[name] = max(b, last_end.get(name, 0))

    def pair_count(counts):
        return sum(n * (n - 1) // 2 for n in counts)

    by_ref, by_hyp = Counter(), Counter()
    for (ref_name, hyp_name), count in pairs.items():
        by_ref[ref_name] += count
        by_hyp[hyp_name] += count
    true_same = pair_count(pairs.values())
    split_base = pair_count(by_ref.values())
    merge_base = pair_count(by_hyp.values())
    return dict(
        der=(miss + false_alarm + confusion) / ref_time if ref_time else None,
        miss_seconds=miss, false_alarm_seconds=false_alarm, confusion_seconds=confusion,
        reference_speaker_seconds=ref_time, reference_speakers=len(actual),
        predicted_speakers=len(guessed), dominant_turns=sum(pairs.values()),
        reference_turns=len(reference), merge_pairs=merge_base - true_same,
        predicted_same_pairs=merge_base, split_pairs=split_base - true_same,
        reference_same_pairs=split_base, short_correct=short_correct, short_total=short_total,
        return_correct=return_correct, return_total=return_total, mapping=mapping,
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--reference', required=True)
    parser.add_argument('--prediction', required=True)
    parser.add_argument('--short', type=float, default=1.2)
    parser.add_argument('--return-gap', type=float, default=30.0)
    args = parser.parse_args()
    print(json.dumps(score(read(args.reference), read(args.prediction), args.short, args.return_gap),
                     ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
