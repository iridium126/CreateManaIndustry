import csv
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from summarize_neighbor_queries import evaluate
from compare_client_frames import compare


class NeighborToolsTest(unittest.TestCase):
    def test_complete_frame_gate_requires_independent_processes_and_candidate_backend(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);rounds=[]
            def window(name,ms,process,backend):
                path=root/(name+'.json');path.write_text(json.dumps(dict(invalid_reason='',measured_seconds=ms,process_id=process,process_started=str(process),backend=backend,context='pack=Photon; source=test; options=known',dimension='overworld',size=[2560,1506],fps_limit=0,vsync=False,gpu='test',driver='test',engine_source_sha256='fixture',diagnostic_receipt_sha256='fixture',auto_throttle=False)))
                with path.with_suffix('.csv').open('w',newline='') as f:
                    w=csv.writer(f);w.writerow(['scene_frame','interval_ms','live','model_input','attack_contract_stage']);w.writerows([[i,ms,65536,65536,''] for i in range(1000)])
                with path.with_name(path.stem+'-camera.csv').open('w',newline='') as f:
                    w=csv.writer(f);w.writerow(['scene_frame','x','y','z','yaw_degrees','pitch_degrees','vertical_fov_degrees']);w.writerows([[i,1,2,3,0,0,70] for i in range(1000)])
                return str(path)
            for r in range(3):
                rounds.append(dict(aa=[window(f'aa{r}a',32,r*4,'production-opengl'),window(f'aa{r}b',32,r*4+1,'production-opengl')],ab=[window(f'ab{r}a',32,r*4+2,'production-opengl'),window(f'ab{r}b',31,r*4+3,'qualified-test-candidate')],candidate_aa=[window(f'ca{r}a',31,r*4+3,'qualified-test-candidate'),window(f'ca{r}b',31,r*4+3,'qualified-test-candidate')]))
            self.assertTrue(compare(dict(rounds=rounds))['frame_gate_pass'])
            rounds[2]['aa']=rounds[0]['aa']
            with self.assertRaises(ValueError):compare(dict(rounds=rounds))

    def test_disabled_pack_header_has_no_session_and_cannot_reuse_source(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);log=root/'client.log';out=root/'profiles.json'
            log.write_text('[CMI profile] frames=240/240; details\\nCapture frames=1..240; 2560x1506; pack=(off); source=old; options=oldhash; world=minecraft:overworld\n')
            subprocess.run([sys.executable,str(Path(__file__).with_name('extract_feather_client_profiles.py')),str(log),str(out)],check=True,capture_output=True)
            entry=json.loads(out.read_text())['captures'][0]
            self.assertFalse(entry['options_are_active']);self.assertEqual(entry['source'],'none');self.assertEqual(entry['options'],'unavailable');self.assertEqual(entry['session'],'unavailable')

    def test_query_screen_never_grants_a_production_or_fps_gate(self):
        with tempfile.TemporaryDirectory() as d:
            paths=[]
            for process in range(3):
                path=Path(d)/f'process{process}.csv';paths.append(path)
                with path.open('w',newline='') as f:
                    w=csv.writer(f);w.writerow('n,queries,layout,comparison,pair,side,backend,gpu_ms,completed_wall_ms'.split(','))
                    for name in ('AA_GL','AA_CUDA','AB_GL_CUDA'):
                        for pair in range(64):
                            for side in (0,1):
                                ms=.8 if name=='AB_GL_CUDA' and side else 1.
                                w.writerow([1000,1000,'uniform',name,pair,side,side,ms,ms])
            result=evaluate(paths)
            self.assertTrue(result['cells'][0]['query_screen_pass']);self.assertFalse(result['production_replacement']);self.assertEqual(result['client_fps_improvement'],'unmeasured')


if __name__=='__main__':unittest.main()
