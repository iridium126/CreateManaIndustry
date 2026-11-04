import csv
import json
import tempfile
import unittest
from pathlib import Path
from topology_summary import summarize


class TopologySummaryTest(unittest.TestCase):
    def report(self, change=None):
        rows = []
        for scenario in range(6):
            for strategy in ('rebuild', 'reference_refit'):
                rows.append(dict(step=10+scenario, strategy=strategy, scenario=scenario, members=65536,
                                 terrain=scenario*10, receipt=0, delay_ms=0, input_wait_ms=.01,
                                 prepare_ms=.1, ias_ms=1, probe_ms=2, seal_ms=.01, owned_bytes=123456))
        metadata = dict(passed=True, submitted=12, consumed=12, retired=12, owned_bytes_after_close=0, elapsed_seconds=900)
        if change:
            change(rows, metadata)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            (path/'topology.json').write_text(json.dumps(metadata))
            with (path/'topology.csv').open('w', newline='') as stream:
                writer=csv.DictWriter(stream, fieldnames=rows[0].keys());writer.writeheader();writer.writerows(rows)
            return summarize(path)

    def test_long_query_control_never_qualifies_full_frame(self):
        report=self.report();self.assertTrue(report['passed']);self.assertTrue(report['long_control_complete'])
        self.assertFalse(report['performance_gate']);self.assertFalse(report['complete_plan_replay'])

    def test_entire_missing_pair_is_not_hidden_by_equal_remaining_inputs(self):
        self.assertFalse(self.report(lambda rows, meta: rows.__delitem__(slice(-2, None)))['passed'])

    def test_nonmatching_geometry_cannot_be_compared(self):
        self.assertFalse(self.report(lambda rows, meta: rows[0].update(terrain=999))['passed'])

    def test_unretired_resources_fail_even_with_matching_counts(self):
        self.assertFalse(self.report(lambda rows, meta: meta.update(owned_bytes_after_close=64))['passed'])

    def test_invalid_timing_and_short_control_cannot_pass_long_gate(self):
        self.assertFalse(self.report(lambda rows, meta: rows[0].update(ias_ms='nan'))['passed'])
        self.assertFalse(self.report(lambda rows, meta: meta.update(elapsed_seconds=5))['long_control_complete'])


if __name__ == '__main__':
    unittest.main()
