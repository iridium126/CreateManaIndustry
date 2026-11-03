import json,tempfile,unittest
from pathlib import Path
from compare_r9_client import evaluate
from test_client_frame_gate import ClientGateTests

class R9ClientTest(unittest.TestCase):
    def test_rejects_small_gain_and_fallback_even_with_fast_query(self):
        with tempfile.TemporaryDirectory() as directory:
            manifest=ClientGateTests().fixture(Path(directory))
            for group in manifest['rounds']:
                for name in set(group['aa']+group['ab']+group['candidate_aa']):
                    p=Path(name);data=json.loads(p.read_text());data['fps_limit']=260
                    if data['backend']=='rt-aabb':data['backend']='rt-aabb-r9-force'
                    p.write_text(json.dumps(data))
            self.assertFalse(evaluate(manifest,'rt-aabb-r9-force')['frame_gate_pass'])
            with self.assertRaises(ValueError):evaluate(manifest,'rt-aabb-r9-force-gpu-result')

if __name__=='__main__':unittest.main()
