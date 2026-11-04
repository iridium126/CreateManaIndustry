import csv
import tempfile
import unittest
from pathlib import Path
from spatial_audit import audit


class LifecycleEvidenceTest(unittest.TestCase):
    def check_rows(self, events, drained=True):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'spatial.csv'
            with path.open('w', newline='') as stream:
                writer = csv.writer(stream)
                writer.writerow(['scene_epoch', 'nonce', 'slot', 'native_abi', 'event'])
                for nonce, slot, event in events:
                    writer.writerow([1, nonce, slot, 12, event])
            return audit(path, drained)

    def test_errors_and_destroyed_identities_consume_once(self):
        events = [(1, 0, 'submitted'), (2, 1, 'submitted'),
                  (1, 0, 'consumed_without_publication'), (2, 1, 'consumed'),
                  (2, 1, 'retired'), (1, 0, 'retired')]
        result = self.check_rows(events)
        self.assertTrue(result['passed'])
        self.assertEqual((result['submitted'], result['consumed'], result['retired']), (2, 2, 2))

    def test_reuse_before_gpu_retirement_is_rejected(self):
        result = self.check_rows([(1, 0, 'submitted'), (1, 0, 'consumed'), (2, 0, 'submitted')])
        self.assertFalse(result['passed'])
        self.assertTrue(any('premature' in error for error in result['errors']))

    def test_latest_only_and_duplicate_consumption_are_rejected(self):
        self.assertFalse(self.check_rows([(1, 0, 'submitted'), (2, 1, 'submitted'), (2, 1, 'consumed')])['passed'])
        self.assertFalse(self.check_rows([(1, 0, 'submitted'), (1, 0, 'consumed'), (1, 0, 'consumed'), (1, 0, 'retired')])['passed'])

    def test_capture_boundary_is_reported_without_inventing_loss(self):
        events = [(1, 0, 'consumed'), (1, 0, 'retired')]
        self.assertTrue(self.check_rows(events, False)['passed'])
        self.assertFalse(self.check_rows(events, True)['passed'])


if __name__ == '__main__':
    unittest.main()
