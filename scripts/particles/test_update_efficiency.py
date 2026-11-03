import csv
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from summarize_update_efficiency import evaluate


class ReplacementGateTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.addCleanup(self.temp.cleanup)
        self.cell = (65536, 1, 'ordinary', 'full')
        self.ab = [self.make(f'ab{i}', .9) for i in range(3)]
        self.aa = [self.make(f'aa{i}', 1) for i in range(3)]

    def make(self, name, ratio, chain='full'):
        directory = self.root/name
        directory.mkdir()
        (directory/'complete.txt').write_text('checks=100\n')
        with (directory/'samples.csv').open('w', newline='') as stream:
            writer = csv.writer(stream)
            writer.writerow('count,mode,scene,chain,sample,baseline_ms,candidate_ms,baseline_cpu_ms,candidate_cpu_ms'.split(','))
            for i in range(64):
                writer.writerow([65536, 1, 'ordinary', chain, i, 2, 2*ratio, .1, .1])
        return directory

    def test_requires_three_reproducible_rounds(self):
        self.assertTrue(evaluate(self.ab, self.aa, {self.cell})['passed'])
        self.ab[2] = self.make('no-gain', 1)
        self.assertFalse(evaluate(self.ab, self.aa, {self.cell})['passed'])
    def test_rule_smoke_cannot_authorize_production_replacement(self):
        (self.ab[0]/'rule-smoke-complete.txt').write_text('checks=100')
        with self.assertRaisesRegex(ValueError, 'not gameplay qualification'):
            evaluate(self.ab,self.aa,{self.cell})

    def test_tail_regression_blocks_an_apparent_median_win(self):
        for directory in self.ab:
            path = directory/'samples.csv'
            with path.open(newline='') as stream:
                rows = list(csv.DictReader(stream))
            for row in rows[-8:]:
                row['candidate_ms'] = '3'
            with path.open('w', newline='') as stream:
                writer = csv.DictWriter(stream, fieldnames=rows[0].keys())
                writer.writeheader()
                writer.writerows(rows)
        result = evaluate(self.ab, self.aa, {self.cell})
        self.assertTrue(result['improvements'])
        self.assertTrue(result['regressions'])
        self.assertFalse(result['passed'])

    def test_missing_cell_sample_or_completion_never_passes(self):
        with self.assertRaises(ValueError):
            evaluate(self.ab, self.aa, {self.cell, (1000, 1, 'ordinary', 'full')})
        path = self.ab[0]/'samples.csv'
        original = path.read_text()
        path.write_text('\n'.join(original.splitlines()[:-1])+'\n')
        with self.assertRaises(ValueError):
            evaluate(self.ab, self.aa, {self.cell})
        path.write_text(original)
        (self.ab[0]/'complete.txt').unlink()
        with self.assertRaises(ValueError):
            evaluate(self.ab, self.aa, {self.cell})

    def test_kernel_measurements_cannot_authorize_full_replacement(self):
        ab = [self.make(f'kernel-ab{i}', .9, 'kernel') for i in range(3)]
        aa = [self.make(f'kernel-aa{i}', 1, 'kernel') for i in range(3)]
        self.assertFalse(evaluate(ab, aa, {(65536, 1, 'ordinary', 'kernel')})['passed'])

    def test_off_pack_cannot_borrow_historical_options(self):
        log = self.root/'debug.log'
        log.write_text('[CMI profile] frames=240/240; details\\nCapture frames=1..240; 1920x1080; pack=(off); source=old; options=12 options / oldhash; session=1; world=minecraft:overworld\n')
        output = self.root/'capture.json'
        subprocess.run([sys.executable, str(Path(__file__).with_name('extract_feather_client_profiles.py')), str(log), str(output)], check=True, capture_output=True)
        entry = json.loads(output.read_text())['captures'][0]
        self.assertFalse(entry['options_are_active'])
        self.assertEqual(entry['source'], 'none')
        self.assertEqual(entry['options'], 'unavailable')
        self.assertEqual(entry['historical_source'], 'old')


if __name__ == '__main__':
    unittest.main()
