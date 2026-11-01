import unittest
import csv,json,tempfile
from pathlib import Path
from unittest.mock import patch
import summarize_r8
from summarize_r8 import metrics
class R8SummaryTests(unittest.TestCase):
 def rows(self,aa=0,gain=1):
  return [dict(comparison=c,pair=str(i),side=str(side),gpu_ms=str(10-(gain if c=='AB' else aa)*side)) for c in ('AA','AC','AB') for i in range(64) for side in (0,1)]
 def test_gain_must_exceed_matched_noise(self):
  self.assertTrue(metrics(self.rows(),'gpu_ms',('AA','AC','AB'))['gate']['passes'])
  self.assertFalse(metrics(self.rows(2,1),'gpu_ms',('AA','AC','AB'))['gate']['passes'])
 def test_duplicate_missing_nonfinite_rejected(self):
  for mutate in (lambda x:x[:-1],lambda x:x+[x[0]],lambda x:[dict(r,gpu_ms='nan') for r in x]):
   with self.assertRaises(ValueError):metrics(mutate(self.rows()),'gpu_ms',('AA','AC','AB'))
 def test_tail_regression_not_hidden_by_median(self):
  rows=self.rows()
  for r in rows:
   if r['comparison']=='AB' and r['side']=='1' and int(r['pair'])<5:r['gpu_ms']='100'
  self.assertFalse(metrics(rows,'gpu_ms',('AA','AC','AB'))['gate']['passes'])
 def test_all_candidates_are_collected(self):
  with tempfile.TemporaryDirectory() as temp:
   data=Path(temp)
   for round in range(3):
    run=data/'measurements-final-v2'/f'sequence-test-r{round}';run.mkdir(parents=True)
    (run/'sequence.json').write_text(json.dumps(dict(passed=True,process_id=round)))
    with (run/'samples.csv').open('w',newline='') as stream:
     out=csv.DictWriter(stream,fieldnames=['candidate','comparison','pair','side','gpu_ms','cpu_ms']);out.writeheader()
     for candidate in ('first','last'):
      for row in self.rows():out.writerow(dict(row,candidate=candidate,comparison={'AA':'AA_base','AC':'AA_candidate','AB':'AB'}[row['comparison']],cpu_ms=row['gpu_ms']))
   with patch.object(summarize_r8,'DATA',data):summarize_r8.main()
   groups=json.loads((data/'measurement-summary.json').read_text())['groups']
   self.assertEqual(set(groups),{'sequence-test/first','sequence-test/last'})
   self.assertTrue(all(g['three_process_gate'] for g in groups.values()))
if __name__=='__main__':unittest.main()
