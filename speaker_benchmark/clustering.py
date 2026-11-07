"""Global speaker clustering: long reliable examples first, short turns assigned afterwards.

No pre-registered speakers. All cluster decisions use vectors from the entire recording.
The thresholds here are STARTING POINTS, NOT validated optimal ERes2NetV2 thresholds.
"""
from dataclasses import dataclass
import numpy as np
from scipy.cluster.hierarchy import linkage, fcluster
from scipy.spatial.distance import pdist

@dataclass
class Segment:
    start: float
    end: float
    embedding: np.ndarray | None = None
    overlap: bool = False

    @property
    def duration(self):
        return self.end - self.start


def normalized(x):
    x = np.asarray(x, dtype=np.float32)
    n = np.linalg.norm(x)
    return x / max(float(n), 1e-10)


def global_cluster(segments, max_cosine_distance=0.32, min_seed_seconds=1.2,
                   short_assign_similarity=0.45, margin=0.05, max_speakers=None,
                   linkage_method='complete', max_seeds=None):
    """Return labels (speaker1-based, 0=unknown) and explanation rows.

    - Reliable long non-overlapping segments define clusters across entire recording.
    - Short/overlapping segments do not independently start clusters.
    - A label can remain unknown instead of forcing an unreliable decision.
    - Hierarchical clustering is O(n^2) memory; use representative pooling for huge recordings.
    """
    labels = np.zeros(len(segments), dtype=np.int32)
    if not 0 <= max_cosine_distance <= 2 or not -1 <= short_assign_similarity <= 1:
        raise ValueError('Invalid cosine threshold')
    if not np.isfinite(min_seed_seconds) or min_seed_seconds <= 0 or not 0 <= margin <= 2:
        raise ValueError('Invalid seed duration or margin')
    if max_speakers is not None and max_speakers < 1:
        raise ValueError('max_speakers must be positive')
    vectors = [
        normalized(s.embedding)
        if (not s.overlap and s.embedding is not None and np.all(np.isfinite(s.embedding))
            and np.linalg.norm(s.embedding) > 1e-10)
        else None for s in segments
    ]
    seed_ids = [i for i, s in enumerate(segments)
                if vectors[i] is not None and not s.overlap and s.duration >= min_seed_seconds]
    if not seed_ids:
        return labels.tolist(), [
            {'index': i, 'reason': 'overlap' if s.overlap else 'no_reliable_seed', 'confidence': ''}
            for i, s in enumerate(segments)
        ]
    if max_seeds is not None:
        if not isinstance(max_seeds, int) or max_seeds < 1:
            raise ValueError('max_seeds must be a positive integer')
        if len(seed_ids) > max_seeds:
            source_ids = seed_ids
            seed_ids = [source_ids[0 if max_seeds == 1 else i * (len(source_ids)-1) // (max_seeds-1)]
                        for i in range(max_seeds)]
    matrix = np.stack([vectors[i] for i in seed_ids])
    if len(seed_ids) == 1:
        seed_labels = np.array([1], dtype=np.int32)
    else:
        # Complete linkage requires support from the least similar seed pair.
        dist = pdist(matrix, metric='cosine')
        tree = linkage(dist, method=linkage_method)
        seed_labels = fcluster(tree, t=max_cosine_distance, criterion='distance').astype(np.int32)
        if max_speakers is not None and len(set(seed_labels)) > max_speakers:
            # Prevent exceeding a known speaker count using the same full dendrogram.
            seed_labels = fcluster(tree, t=max_speakers, criterion='maxclust').astype(np.int32)
    # Stable IDs in order of first appearance.
    conversion = {}
    for lab in seed_labels:
        if int(lab) not in conversion:
            conversion[int(lab)] = len(conversion) + 1
    seed_labels = np.array([conversion[int(x)] for x in seed_labels], dtype=np.int32)
    for i, label in zip(seed_ids, seed_labels):
        labels[i] = label
    # Give each unique second of clean speech a vote, capped at one window.
    weights = np.array([min(4.0, segments[i].duration) for i in seed_ids])
    centers = np.zeros((len(conversion), matrix.shape[1]), dtype=np.float32)
    np.add.at(centers, seed_labels - 1, matrix * weights[:, None])
    centers = np.stack([normalized(center) for center in centers])

    explanations = []
    for i, s in enumerate(segments):
        if labels[i]:
            explanations.append({'index': i, 'reason': 'global_seed', 'confidence': ''})
            continue
        if vectors[i] is None:
            reason = 'overlap' if s.overlap else 'no_embedding'
            explanations.append({'index': i, 'reason': reason, 'confidence': ''})
            continue
        scores = centers @ vectors[i]
        best = int(scores.argmax())
        best_score = float(scores[best])
        second_score = float(np.partition(scores, -2)[-2]) if len(scores) > 1 else -1.0
        if best_score >= short_assign_similarity and best_score-second_score >= margin:
            labels[i] = best + 1
            reason = 'assigned_to_existing'
        else:
            reason = 'ambiguous_short'
        explanations.append({'index': i, 'reason': reason, 'confidence': round(best_score, 4)})
    chronological = sorted(range(len(segments)), key=lambda i: (segments[i].start, i))
    conversion = {0: 0}
    for i in chronological:
        label = int(labels[i])
        conversion.setdefault(label, len(conversion))
    return [conversion[int(label)] for label in labels], explanations


def unique_intervals(rows):
    """Deduplicate EXACT repetitions from overlapping inference windows.
    Does NOT silently merge genuine overlap or different segmentation intervals.
    """
    seen, result = set(), []
    for row in sorted(rows, key=lambda s: (s['start'], s['end'])):
        key = (round(float(row['start']), 3), round(float(row['end']), 3))
        if key not in seen:
            seen.add(key)
            result.append(row)
    return result
