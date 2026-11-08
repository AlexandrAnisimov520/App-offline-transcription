"""Behavioral checks: overlap, abstention, and preservation of timestamps."""
import unittest
import numpy as np
from refine_speaker_groups import refine


class GroupRefinementTests(unittest.TestCase):
    def test_simultaneous_voices_stay_distinct_even_with_identical_embeddings(self):
        rows = [(0., 2., 'a'), (1., 3., 'b')]
        result = refine(rows, ['a','b'], np.array([[1.,0.],[1.,0.]]), np.ones(2), .5)
        self.assertNotEqual(result[0][2], result[1][2])

    def test_repeated_voice_can_merge_without_changing_timestamps(self):
        rows = [(0., 2., 'a'), (3., 5., 'b')]
        result = refine(rows, ['a','b'], np.array([[1.,0.],[1.,0.]]), np.ones(2), .5)
        self.assertEqual(result[0][2], result[1][2])
        self.assertEqual([(a,b) for a,b,s in rows], [(a,b) for a,b,s in result])

    def test_unusable_embedding_is_not_forced_into_a_voice(self):
        rows = [(0.,2.,'a'), (3.,4.,'b')]
        result = refine(rows, ['a','b'], np.array([[1.,0.],[0.,0.]]), np.ones(2), .35)
        self.assertNotEqual(result[0][2],result[1][2])

    def test_overlap_constraint_survives_transitive_merge(self):
        rows = [(0.,2.,'a'), (3.,5.,'b'), (1.,2.,'c')]
        result = refine(rows, ['a','b','c'], np.array([[1.,0.]]*3), np.ones(3), .35)
        self.assertNotEqual(result[0][2], result[2][2])


if __name__ == '__main__': unittest.main()
