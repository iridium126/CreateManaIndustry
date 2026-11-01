import hashlib,json,struct,tempfile,unittest
from pathlib import Path
from r6_query_sequence import load_sequence

class SequenceTests(unittest.TestCase):
    def fixture(self,root):
        frames=[]
        for i in range(16):
            data=struct.pack('<16I',1,1,i,0,1,1,131072,32,*([0]*8))+struct.pack('<3fI3fI',1,2,3,0,4,5,6,0)
            file=f'frame-{i:04d}.bin';(root/file).write_bytes(data)
            frames.append(dict(file=file,scene_frame=100+i,input_generation=i,points=1,time=i/60,dt=1/60,sha256=hashlib.sha256(data).hexdigest()))
        manifest=dict(schema=1,complete=True,failure_reason='',requested_frames=16,record_bytes=32,header_bytes=64,frames=frames)
        return manifest
    def test_reject_missing_invalid_or_mutated_sequences(self):
        for kind in ('valid','gap','generation','overflow','partial','checksum','order','duplicate'):
            with self.subTest(kind=kind),tempfile.TemporaryDirectory() as directory:
                root=Path(directory);manifest=self.fixture(root)
                if kind=='gap':manifest['frames'][8]['scene_frame']+=1
                if kind=='generation':manifest['frames'][8]['input_generation']+=1
                if kind=='overflow':manifest['failure_reason']='queue overflow';manifest['complete']=False
                if kind=='partial':manifest['frames'].pop()
                if kind=='checksum':(root/'frame-0004.bin').write_bytes(b'bad')
                if kind=='order':manifest['frames'].reverse()
                if kind=='duplicate':
                    data=bytearray((root/'frame-0004.bin').read_bytes());struct.pack_into('<I',data,64+12,131072);(root/'frame-0004.bin').write_bytes(data);manifest['frames'][4]['sha256']=hashlib.sha256(data).hexdigest()
                (root/'manifest.json').write_text(json.dumps(manifest))
                if kind=='valid':self.assertEqual(len(load_sequence(root)['frames']),16)
                else:
                    with self.assertRaises(ValueError):load_sequence(root)
    def test_v2_actual_origin_may_differ_but_identity_stage_and_radius_are_required(self):
        for kind in ('valid','negative-radius','stage','identity','nan'):
            with self.subTest(kind=kind),tempfile.TemporaryDirectory() as directory:
                root=Path(directory);manifest=self.fixture(root);manifest.update(schema=2,record_bytes=64)
                for i,frame in enumerate(manifest['frames']):
                    data=bytearray((root/frame['file']).read_bytes());struct.pack_into('<I',data,28,64)
                    extra=bytearray(struct.pack('<4f4I',10,20,30,.45,0,0,1,1))
                    if i==8:
                        if kind=='negative-radius':struct.pack_into('<f',extra,12,-1)
                        if kind=='stage':struct.pack_into('<I',extra,28,2)
                        if kind=='identity':struct.pack_into('<I',extra,20,1)
                        if kind=='nan':struct.pack_into('<f',extra,0,float('nan'))
                    data+=extra;(root/frame['file']).write_bytes(data);frame['sha256']=hashlib.sha256(data).hexdigest()
                (root/'manifest.json').write_text(json.dumps(manifest))
                if kind=='valid':self.assertEqual(load_sequence(root)['schema'],2)
                else:
                    with self.assertRaises(ValueError):load_sequence(root)

    def test_v3_dome_has_an_independent_position_stage_and_generation(self):
        for kind in ('valid','generation','checksum','negative-radius','stage','missing'):
            with self.subTest(kind=kind),tempfile.TemporaryDirectory() as directory:
                root=Path(directory);manifest=self.fixture(root);manifest.update(schema=3,record_bytes=64)
                for i,frame in enumerate(manifest['frames']):
                    raw=bytearray((root/frame['file']).read_bytes());struct.pack_into('<I',raw,28,64);raw+=struct.pack('<4f4I',10,20,30,.45,0,0,1,1);(root/frame['file']).write_bytes(raw);frame['sha256']=hashlib.sha256(raw).hexdigest()
                    payload=bytearray(struct.pack('<16I',1,1,i+1,0,1,0,131072,80,*([0]*8))+struct.pack('<3fI3fI4I8f',1,2,3,0,4,5,6,0,1,1,1,2,1,2,3,.5,1,2,3,.7))
                    if i==8:
                        if kind=='generation':struct.pack_into('<I',payload,8,i)
                        if kind=='negative-radius':struct.pack_into('<f',payload,64+60,-.1)
                        if kind=='stage':struct.pack_into('<I',payload,64+44,1)
                    file=f'frame-{i:04d}-dome.bin';(root/file).write_bytes(payload);frame['dome']=dict(file=file,present=True,points=1,candidate_generation=i+1,record_bytes=80,sha256=hashlib.sha256(payload).hexdigest())
                    if i==8 and kind=='checksum':(root/file).write_bytes(b'bad')
                    if i==8 and kind=='missing':(root/file).unlink()
                (root/'manifest.json').write_text(json.dumps(manifest))
                if kind=='valid':self.assertEqual(load_sequence(root)['schema'],3)
                else:
                    with self.assertRaises((ValueError,FileNotFoundError)):load_sequence(root)

if __name__=='__main__':unittest.main()
