"""Resolve window boundaries on the 16 kHz sample grid before extracting audio."""
from collections import defaultdict
from dataclasses import dataclass
import math

RATE = 16000


@dataclass(frozen=True)
class Turn:
    start: int
    end: int
    overlap: bool


def prepare_turns(rows, sample_count, max_window_seconds=4.0):
    if not math.isfinite(max_window_seconds) or max_window_seconds < 2:
        raise ValueError('max_window_seconds must be finite and at least 2')
    limit = round(max_window_seconds * RATE)
    exact = {}
    for row in rows:
        start, end = float(row['start']), float(row['end'])
        if not (math.isfinite(start) and math.isfinite(end)
                and 0 <= start < end <= (sample_count + 16) / RATE):
            raise ValueError(f'Invalid segment: {start}, {end}')
        a, b = round(start * RATE), min(sample_count, round(end * RATE))
        if a >= b:
            raise ValueError(f'Empty segment on sample grid: {start}, {end}')
        overlap = str(row.get('overlap', '')).strip().lower() in ('1', 'true', 'yes')
        exact[a, b] = exact.get((a, b), False) or overlap

    # Coalesce near copies using the original anchor, so a chain of shifted
    # windows cannot expand into a long unrelated turn.
    windows = []
    anchor = None
    for (a, b), overlap in sorted(exact.items()):
        if overlap:
            windows.append(Turn(a, b, True))
            continue
        if anchor is not None:
            x, y, index = anchor
            intersection = min(b, y) - max(a, x)
            if intersection >= 0.8 * (max(b, y) - min(a, x)):
                windows[index] = Turn(x, max(b, windows[index].end), False)
                continue
        anchor = a, b, len(windows)
        windows.append(Turn(a, b, False))

    events = defaultdict(list)
    for index, turn in enumerate(windows):
        events[turn.start].append((index, True))
        events[turn.end].append((index, False))
    active = set()
    previous = 0
    spans = []
    for position, changes in sorted(events.items()):
        if active:
            owner = next(iter(active)) if len(active) == 1 else None
            if owner is not None and windows[owner].overlap:
                owner = None
            if spans and spans[-1][1] == previous and spans[-1][2] == owner:
                spans[-1] = spans[-1][0], position, owner
            else:
                spans.append((previous, position, owner))
        for index, begins in changes:
            if begins:
                active.add(index)
            else:
                active.remove(index)
        previous = position

    for a, b, owner in spans:
        count = 1 if owner is None else math.ceil((b - a) / limit)
        for i in range(count):
            yield Turn(a + (b - a) * i // count,
                       a + (b - a) * (i + 1) // count, owner is None)
