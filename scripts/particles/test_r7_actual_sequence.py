import hashlib,json,struct,tempfile,unittest
from pathlib import Path
from prepare_r7_actual_sequence import export

class ActualSequenceTests(unittest.TestCase):
    def fixture(self,root,schema=3):
        frames=[]
        for i in range(16):
            file=f'frame-{i:04d}.bin';data=struct.pack('<16I',1,1,i,0,1,1,131072,64,*([0]*8))+struct.pack('<3fI3fI4f4I',1,2,3,0,0,0,0,0,1,2,3,1,0,0,1,1);(root/file).write_bytes(data)
            f=dict(file=file,sha256=hashlib.sha256(data).hexdigest(),points=1,scene_frame=i+100,input_generation=i,time=i/60,dt=1/60)
            if schema==3:
                file=f'frame-{i:04d}-dome.bin';d=struct.pack('<16I',1,1,i+1,0,1,0,131072,80,*([0]*8))+struct.pack('<3fI3fI4I4f4f',1,2,3,0,0,0,0,0,1,1,1,2,1,2,3,.5,1,2,3,.75);(root/file).write_bytes(d);f['dome']=dict(file=file,sha256=hashlib.sha256(d).hexdigest(),record_bytes=80,points=1,present=True,candidate_generation=i+1)
            frames.append(f)
        (root/'manifest.json').write_text(json.dumps(dict(schema=schema,complete=True,failure_reason='',requested_frames=16,record_bytes=64,header_bytes=64,frames=frames)))
    def test_actual_stages_and_overwrite_refusal(self):
        for stage,radius in (('ordinary',1),('dome-solve',.5),('dome-apply',.75)):
            with self.subTest(stage=stage),tempfile.TemporaryDirectory() as directory:
                root=Path(directory);source=root/'source';source.mkdir();self.fixture(source);out=root/'out';result=export(source,out,stage)
                self.assertEqual(result['max_radius'],radius);self.assertEqual(result['queries'],1);self.assertEqual(struct.unpack('<I',(out/'frame-0000-owners.bin').read_bytes())[0],0)
                with self.assertRaises(ValueError):export(source,out,stage)
    def test_missing_position_stage_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);self.fixture(root,2)
            with self.assertRaises(ValueError):export(root,root/'out','dome-solve')
if __name__=='__main__':unittest.main()
