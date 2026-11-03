import unittest
from summarize_r9 import evaluate

def samples(gain=1.):
    rows=[]
    for label in ('AA_production','AA_native','AB_production_native'):
        for pair in range(64):
            for side in (0,1):rows.append(dict(comparison=label,pair=str(pair),side=str(side),gpu_ms=str(5-(gain if side and label=='AB_production_native' else 0)),reference_fallbacks='0'))
    return rows

class GateTest(unittest.TestCase):
    def test_complete_clear_gain(self):self.assertTrue(evaluate(samples())['gate']['passes'])
    def test_small_gain_is_rejected(self):self.assertFalse(evaluate(samples(.05))['gate']['passes'])
    def test_regression_is_rejected(self):self.assertFalse(evaluate(samples(-1))['gate']['passes'])
    def test_missing_and_duplicate_samples(self):
        rows=samples()
        with self.assertRaises(ValueError):evaluate(rows[:-1])
        with self.assertRaises(ValueError):evaluate(rows+[rows[0]])
    def test_any_fallback_invalidates_comparison(self):
        rows=samples();rows[20]['reference_fallbacks']='1'
        with self.assertRaises(ValueError):evaluate(rows)
    def test_tail_regression_rejects_fast_median(self):
        rows=samples()
        for r in rows:
            if r['comparison']=='AB_production_native' and r['side']=='1' and int(r['pair'])>=60:r['gpu_ms']='10'
        self.assertFalse(evaluate(rows)['gate']['passes'])

if __name__=='__main__':unittest.main()
