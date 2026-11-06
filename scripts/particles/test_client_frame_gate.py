"""Synthetic files test gate logic only; they are never performance evidence."""
import csv,json,tempfile,unittest
import shutil
from pathlib import Path
from compare_client_frames import compare
class ClientGateTests(unittest.TestCase):
    def fixture(self,root):
        rounds=[]
        for r in range(3):
            paths=[]
            for side,(backend,ms) in enumerate((('production-opengl',32),('production-opengl',32.01),('rt-aabb',31),('rt-aabb',31.01))):
                p=root/f'r{r}-s{side}.json';meta=dict(dimension='overworld',size=[1920,1080],fps_limit=0,vsync=False,gpu='fixture',driver='fixture',context=f'pack=fixture; source=123; options=abc; session={r}; backend={backend}; engine=abc',engine_source_sha256='abc',diagnostic_receipt_sha256='fixture-receipt',auto_throttle=False,invalid_reason='',measured_seconds=ms,process_id=r+1,process_started=f'fixture-process-{r}',backend=backend)
                p.write_text(json.dumps(meta))
                with p.with_suffix('.csv').open('w',newline='') as f:
                    w=csv.writer(f);w.writerow(('scene_frame','interval_ms','live','model_input','attack_contract_stage'));w.writerows((i,ms,65536,65536,'') for i in range(1000))
                with p.with_name(p.stem+'-camera.csv').open('w',newline='') as f:
                    w=csv.writer(f);w.writerow(('scene_frame','x','y','z','yaw_degrees','pitch_degrees','vertical_fov_degrees'));w.writerows((i,1,2,3,0,0,70) for i in range(1000))
                paths.append(str(p))
            rounds.append(dict(aa=paths[:2],candidate_aa=paths[2:],ab=[paths[0],paths[2]]))
        return dict(rounds=rounds)
    def test_backend_is_allowed_to_differ_but_camera_options_process_counts_are_not(self):
        for mutation in ('valid','options','process','counts','throttle','missing_candidate_aa','camera'):
            with self.subTest(mutation=mutation),tempfile.TemporaryDirectory() as directory:
                root=Path(directory);manifest=self.fixture(root);p=Path(manifest['rounds'][1]['ab'][1]);d=json.loads(p.read_text())
                if mutation=='options':d['context']=d['context'].replace('options=abc','options=other')
                if mutation=='process':d['process_id']=1;d['process_started']='fixture-process-0'
                if mutation=='throttle':d['auto_throttle']=True
                if mutation=='missing_candidate_aa':del manifest['rounds'][1]['candidate_aa']
                if mutation=='camera':
                    camera=p.with_name(p.stem+'-camera.csv');camera.write_text(camera.read_text().replace(',0,0,70',',0,-90,70'))
                if mutation=='counts':p.with_suffix('.csv').write_text(p.with_suffix('.csv').read_text().replace('65536','32000'))
                p.write_text(json.dumps(d))
                if mutation=='valid':self.assertTrue(compare(manifest)['frame_gate_pass'])
                else:
                    with self.assertRaises(ValueError):compare(manifest)
    def test_particle_engine_simulation_uses_independent_windows_and_p50_gate(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            manifest=self.fixture(root)
            manifest.update(comparison='particle-engine',factor='simulation')
            for number,round in enumerate(manifest['rounds']):
                for index,source in enumerate(round['aa']+round['candidate_aa']):
                    p=Path(source)
                    data=json.loads(p.read_text())
                    active='gl' if index<2 else 'rt'
                    data.update(backend=f'requested={active} active={active} abi=12',build_sha256='a'*64,
                                rt_camera_requested=True,receiving_requested=True,shadow_quality='FULL',
                                process_id=number*6+index)
                    data.pop('diagnostic_receipt_sha256')
                    p.write_text(json.dumps(data))
                for index,source in enumerate(round['ab']):
                    p=Path(source)
                    dest=p.with_name(p.stem+'-ab.json')
                    data=json.loads(p.read_text())
                    data['process_id']=number*6+4+index
                    dest.write_text(json.dumps(data))
                    shutil.copyfile(p.with_suffix('.csv'),dest.with_suffix('.csv'))
                    shutil.copyfile(p.with_name(p.stem+'-camera.csv'),dest.with_name(dest.stem+'-camera.csv'))
                    round['ab'][index]=str(dest)
            self.assertTrue(compare(manifest)['frame_gate_pass'])
            manifest['rounds'][0]['ab'][0]=manifest['rounds'][0]['aa'][0]
            with self.assertRaisesRegex(ValueError,'independent'):
                compare(manifest)
if __name__=='__main__':unittest.main()
