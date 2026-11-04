import argparse
import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest
import particle_tool as tool


class ClientEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(dir=tool.ROOT/'build/particle-engine')
        self.root=Path(self.temp.name)
        self.groups={}
        for label,ms,offset in [('a',35,0),('aa',35.01,3),('b',34.5,6)]:
            self.groups[label]=[self.window(label,i,ms,offset+i) for i in range(3)]

    def tearDown(self): self.temp.cleanup()

    def window(self,label,index,ms,process):
        path=self.root/f'{label}{index}.csv'
        path.write_text('interval_ms,attack_contract_stage,live\n'+f'{ms},flight,65536\n'*1000)
        path.with_name(path.stem+'-camera.csv').write_text('x,y,z,yaw_degrees,pitch_degrees,vertical_fov_degrees\n'+'0,0,0,0,0,70\n'*1000)
        data={'process_id':process,'process_started':str(process),'context':f'pack=fixed; backend={label}; engine={label}; options=same',
              'dimension':'overworld','size':[1920,1080],'fps_limit':260,'vsync':False,'shadow_quality':'FULL',
              'rift_config':{},'gpu':'fixture','driver':'fixture','display_interpolation':True,'query_budget':'2/32/4'}
        path.with_suffix('.json').write_text(json.dumps(data))
        return path

    def analyze(self):
        with contextlib.redirect_stdout(io.StringIO()):
            tool.analyze(argparse.Namespace(**self.groups,output=self.root/'result'))
        return json.loads((self.root/'result/analysis.json').read_text())

    def test_small_real_gain_does_not_require_twenty_percent(self):
        self.assertTrue(self.analyze()['performance_gate'])

    def test_different_attack_stage_cannot_qualify(self):
        path=self.groups['b'][0];path.write_text(path.read_text().replace('flight','Dome'))
        report=self.analyze();self.assertFalse(report['performance_gate']);self.assertIn('attack stage coverage',report['matching'][0]['b'])

    def test_missing_camera_cannot_qualify(self):
        path=self.groups['b'][0];path.with_name(path.stem+'-camera.csv').unlink()
        self.assertFalse(self.analyze()['performance_gate'])

    def test_same_process_is_not_an_independent_round(self):
        path=self.groups['b'][0].with_suffix('.json');data=json.loads(path.read_text());data['process_id']=0;data['process_started']='0';path.write_text(json.dumps(data))
        with self.assertRaisesRegex(ValueError,'independent process launches'):self.analyze()

    def test_incomplete_window_is_rejected(self):
        path=self.groups['b'][0];path.write_text('interval_ms\n1\n')
        with self.assertRaisesRegex(ValueError,'1,000 frames'):tool.frame_window(path)

    def test_rt_cannot_qualify_with_pending_or_unretired_work(self):
        path=self.groups['b'][0];metadata_path=path.with_suffix('.json');data=json.loads(metadata_path.read_text())
        data['spatial_unretired_at_export']=1;metadata_path.write_text(json.dumps(data))
        with self.assertRaisesRegex(ValueError,'did not drain'):tool.frame_window(path)
        data['spatial_unretired_at_export']=0;data['backend']='requested=rt active=rt-waiting abi=12';metadata_path.write_text(json.dumps(data))
        with self.assertRaisesRegex(ValueError,'not active'):tool.frame_window(path)


if __name__=='__main__':unittest.main()
