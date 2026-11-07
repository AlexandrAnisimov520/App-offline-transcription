"""Local Pyannote segmentation-3.0 ONNX to speech/overlap intervals."""
import numpy as np
from scipy.optimize import linear_sum_assignment

from intervals import RATE

# Powerset order: silence, singleton slots 1–3, and the three pairs.
CLASSES = np.array([0, 1, 2, 3, -1, -1, -1], dtype=np.int64)


def stitch_ids(previous, current, hop, next_id):
    """Align local slots in shared context; the IDs only preserve seam continuity."""
    left = previous[hop:]
    right = current[:len(left)]
    left_ids = np.unique(left[left > 0])
    matches = {}
    if len(left_ids):
        right_ids = np.arange(1, 4)
        shared = np.array([[(np.logical_and(left == a, right == b)).sum()
                            for b in right_ids] for a in left_ids])
        for i, j in zip(*linear_sum_assignment(shared, maximize=True)):
            union = (left == left_ids[i]).sum() + (right == right_ids[j]).sum() - shared[i, j]
            if shared[i, j] >= 4 and shared[i, j] >= 0.5 * union:
                matches[right_ids[j]] = left_ids[i]
    lookup = np.array([0, 0, 0, 0, -1], dtype=np.int64)
    for slot in range(1, 4):
        if slot in matches:
            lookup[slot] = matches[slot]
        else:
            lookup[slot] = next_id
            next_id += 1
    return lookup[current], next_id


def activity_rows(activity, shift, first_edge, sample_count):
    starts = np.r_[0, np.flatnonzero(np.diff(activity)) + 1, len(activity)]
    labels = activity[starts[:-1]].copy()
    # Brief internal dropouts only, with matching speech on both sides.
    for i in range(1, len(labels) - 1):
        if (labels[i] == 0 and labels[i - 1] > 0 and labels[i - 1] == labels[i + 1]
                and (starts[i + 1] - starts[i]) * shift <= RATE // 10):
            labels[i] = labels[i - 1]
    for i, label in enumerate(labels):
        if label == 0 or (i and label == labels[i - 1]):
            continue
        end = i + 1
        while end < len(labels) and labels[end] == label:
            end += 1
        a = min(sample_count, first_edge + starts[i] * shift)
        b = min(sample_count, first_edge + starts[end] * shift)
        if a < b:
            yield dict(start=a / RATE, end=b / RATE, overlap=int(label == -1))


def segment_audio(audio, session):
    meta = session.get_modelmeta().custom_metadata_map
    required = {'model_type': 'pyannote-segmentation-3.0', 'sample_rate': '16000',
                'num_speakers': '3', 'num_classes': '7', 'powerset_max_classes': '2'}
    if any(meta.get(key) != value for key, value in required.items()):
        raise ValueError('Expected a local Pyannote segmentation-3.0 ONNX with powerset metadata')
    window, field, shift = (int(meta[key]) for key in
                            ('window_size', 'receptive_field_size', 'receptive_field_shift'))
    if not 0 < shift <= field < window:
        raise ValueError('Invalid Pyannote frame geometry')
    hop = window // (2 * shift)
    frames = 1 + (window - field) // shift
    if not 0 < hop < frames:
        raise ValueError('Pyannote windows must share frames')
    cut = (frames - hop) // 2
    inputs = session.get_inputs()
    if len(inputs) != 1:
        raise ValueError('Expected one Pyannote PCM input')
    if not len(audio):
        return []

    chunks = []
    previous = np.empty(0, dtype=np.int64)
    start = left = 0
    next_id = 1
    while True:
        chunk = audio[start:start + window]
        padded = np.pad(chunk, (0, window - len(chunk)))
        logits = session.run(None, {inputs[0].name: padded[None, None, :]})[0]
        if logits.shape != (1, frames, 7) or not np.isfinite(logits).all():
            raise ValueError(f'Invalid Pyannote logits: {logits.shape}')
        current = CLASSES[logits[0].argmax(axis=-1)]
        current, next_id = stitch_ids(previous, current, hop, next_id)
        if len(previous):
            chunks.append(previous[left:hop + cut])
            left = cut
        if start + window >= len(audio):
            chunks.append(current[left:])
            break
        previous = current
        start += hop * shift
    return list(activity_rows(np.concatenate(chunks), shift, (field - shift) // 2, len(audio)))
