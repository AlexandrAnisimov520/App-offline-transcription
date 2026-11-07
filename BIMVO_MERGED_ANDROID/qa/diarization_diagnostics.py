"""Exact interval diagnostics complementing DER; no reference information enters inference."""
from collections import Counter, defaultdict
import numpy as np
from scipy.optimize import linear_sum_assignment


def diagnostics(reference, hypothesis):
    events = defaultdict(list)
    for side, rows in enumerate((reference, hypothesis)):
        for a, b, speaker in rows:
            # Unknown identity still means detected speech for binary VAD metrics.
            events[a].append((side, speaker, 1))
            events[b].append((side, speaker, -1))
    active = [Counter(), Counter()]
    seconds = [Counter(), Counter()]
    intersections = Counter()
    ref_speech = hyp_speech = joint_speech = 0.0
    ref_overlap = detected_overlap = overlap_joint = 0.0
    previous = 0.0
    for t, changes in sorted(events.items()):
        dt = t - previous
        r, h = active
        for side in (0, 1):
            for speaker in active[side]:
                if speaker != 'UNKNOWN': seconds[side][speaker] += dt
        for a in r:
            for b in h:
                if a != 'UNKNOWN' and b != 'UNKNOWN': intersections[a, b] += dt
        if r: ref_speech += dt
        if h: hyp_speech += dt
        if r and h: joint_speech += dt
        if len(r) > 1: ref_overlap += dt
        known_h = sum(label != 'UNKNOWN' for label in h)
        if known_h > 1: detected_overlap += dt
        if len(r) > 1 and known_h > 1: overlap_joint += dt
        for side, speaker, delta in changes:
            active[side][speaker] += delta
            if active[side][speaker] == 0: del active[side][speaker]
        previous = t
    refs, hyps = sorted(seconds[0]), sorted(seconds[1])
    iou = np.zeros((len(refs), len(hyps)), dtype=float)
    for i, a in enumerate(refs):
        for j, b in enumerate(hyps):
            inter = intersections[a, b]
            union = seconds[0][a] + seconds[1][b] - inter
            iou[i, j] = inter / union if union > 0 else 0
    matched_iou = 0.0
    if refs and hyps:
        ri, hi = linear_sum_assignment(iou, maximize=True)
        matched_iou = float(iou[ri, hi].sum())
    precision = joint_speech / hyp_speech if hyp_speech else 0.0
    recall = joint_speech / ref_speech if ref_speech else 0.0
    return {'jer': 1 - matched_iou / len(refs) if refs else None,
            'vad_precision': precision, 'vad_recall': recall,
            'vad_f1': 2 * precision * recall / (precision + recall) if precision + recall else 0.0,
            'reference_speech_union_seconds': ref_speech, 'hypothesis_speech_union_seconds': hyp_speech,
            'reference_overlap_seconds': ref_overlap,
            'overlap_precision': overlap_joint / detected_overlap if detected_overlap else 0.0,
            'overlap_recall': overlap_joint / ref_overlap if ref_overlap else None,
            'note': 'JER averages reference-speaker IoU error after optimal assignment; inspect with DER '
                    'because unmatched extra hypothesis speakers are not separately penalized. '
                    'VAD metrics use binary speech union; overlap requires >=2 concurrent speaker labels.'}
