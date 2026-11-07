import unittest

import numpy as np

from clustering import Segment, global_cluster
from features import extract_fbank
from intervals import RATE, Turn, prepare_turns
from pyannote_segments import activity_rows, stitch_ids
from score import score


class PipelineRegressions(unittest.TestCase):
    def test_marked_overlap_wins_and_clean_flanks_survive(self):
        rows = [dict(start=0, end=3), dict(start=.05, end=3.05),
                dict(start=1, end=2), dict(start=1, end=2, overlap=1)]
        self.assertEqual(list(prepare_turns(rows, 4 * RATE)),
                         [Turn(0, RATE, False), Turn(RATE, 2 * RATE, True),
                          Turn(2 * RATE, 48800, False)])

    def test_partial_overlap_never_feeds_shared_samples(self):
        rows = [dict(start=0, end=2), dict(start=1, end=3)]
        self.assertEqual(list(prepare_turns(rows, 3 * RATE)),
                         [Turn(0, RATE, False), Turn(RATE, 2 * RATE, True),
                          Turn(2 * RATE, 3 * RATE, False)])

    def test_long_window_has_no_overlap_or_short_tail(self):
        turns = list(prepare_turns([dict(start=0, end=8.1)], 9 * RATE))
        self.assertEqual(len(turns), 3)
        self.assertEqual(turns[0].start, 0)
        self.assertEqual(turns[-1].end, round(8.1 * RATE))
        self.assertTrue(all(a.end == b.start for a, b in zip(turns, turns[1:])))
        self.assertTrue(all(2.6 * RATE < t.end - t.start < 2.8 * RATE for t in turns))

    def test_complete_linkage_blocks_bridge(self):
        angles = np.deg2rad([0., 38., 48.])
        turns = [Segment(i * 3, i * 3 + 2, np.array([np.cos(a), np.sin(a)]))
                 for i, a in enumerate(angles)]
        self.assertEqual(global_cluster(turns, linkage_method='complete')[0], [1, 2, 2])
        self.assertEqual(global_cluster(turns, linkage_method='average')[0], [1, 1, 1])

    def test_overlap_and_invalid_vectors_cannot_seed(self):
        turns = [Segment(0, 2, np.ones(2), overlap=True), Segment(2, 4, np.zeros(2)),
                 Segment(4, 6, np.array([np.nan, 0])), Segment(6, 8, np.ones(2))]
        self.assertEqual(global_cluster(turns)[0], [0, 0, 0, 1])

    def test_fbank_uses_10ms_shift_and_mean_centering(self):
        wave = np.random.default_rng(123).normal(0, .04, RATE).astype(np.float32)
        tensor = extract_fbank(wave)
        self.assertEqual(tensor.shape, (98, 80))
        self.assertEqual(tensor.dtype, np.float32)
        self.assertTrue(np.isfinite(tensor).all())
        np.testing.assert_allclose(tensor.mean(axis=0), 0, atol=5e-6)

    def test_der_overlap_unknown_and_false_alarm(self):
        ref = [(0., 2., 'A'), (1., 3., 'B')]
        pred = [(0., 1., 'X'), (0., 1., 'X'), (1., 2., 'UNKNOWN'), (2., 3.5, 'Y')]
        result = score(ref, pred)
        self.assertEqual(result['reference_speaker_seconds'], 4.)
        self.assertEqual(result['miss_seconds'], 2.)
        self.assertEqual(result['false_alarm_seconds'], .5)
        self.assertEqual(result['confusion_seconds'], 0.)
        self.assertEqual(result['der'], .625)

    def test_pyannote_slots_are_matched_across_window_seam(self):
        previous = np.array([10] * 4 + [11] * 4 + [12] * 4)
        current = np.array([2] * 4 + [1] * 4 + [-1, 0, 3, 3])
        aligned, next_id = stitch_ids(previous, current, 4, 13)
        np.testing.assert_array_equal(aligned, [11] * 4 + [12] * 4 + [-1, 0, 13, 13])
        self.assertEqual(next_id, 14)

    def test_pyannote_dropout_is_filled_but_overlap_survives(self):
        rows = list(activity_rows(np.array([1, 1, 0, 1, -1, 2]), 160, 0, 960))
        self.assertEqual(rows, [dict(start=0., end=.04, overlap=0),
                                dict(start=.04, end=.05, overlap=1),
                                dict(start=.05, end=.06, overlap=0)])


if __name__ == '__main__':
    unittest.main()
