import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import unittest
import numpy as np
from clustering import Segment, global_cluster, unique_intervals

class ClusteringTests(unittest.TestCase):
    def test_reappearing_speaker(self):
        turns = [Segment(0, 2, np.array([1.,0.,0.])), Segment(4,6,np.array([0.,1.,0.])),
                 Segment(160,162,np.array([0.99,.01,0.]))]
        labels, _ = global_cluster(turns, max_cosine_distance=.2)
        self.assertEqual(labels, [1,2,1])

    def test_short_does_not_create_speaker(self):
        turns = [Segment(0,2,np.array([1.,0.,0.])), Segment(3,3.3,np.array([0.,0.,1.]))]
        labels, _ = global_cluster(turns, max_cosine_distance=.2)
        self.assertEqual(labels, [1,0])

    def test_three_distinct_speakers(self):
        turns = [Segment(0,2,np.array([1.,0.,0.])), Segment(3,5,np.array([0.,1.,0.])),
                 Segment(6,8,np.array([0.,0.,1.]))]
        labels, _ = global_cluster(turns, max_cosine_distance=.2)
        self.assertEqual(len(set(labels)), 3)

    def test_no_duplicate_windows(self):
        data=[{'start':0.,'end':2.},{'start':0.,'end':2.},{'start':1.,'end':3.}]
        self.assertEqual(len(unique_intervals(data)),2)

if __name__ == '__main__':
    unittest.main()
