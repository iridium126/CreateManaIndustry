"""Moving synthetic test data for the bounded format and periodic replay adapter only."""
import argparse,hashlib,json,math,struct
from pathlib import Path

def make(root,count=65):
    root.mkdir(parents=True,exist_ok=False);frames=[]
    for index in range(16):
        header=struct.pack('<16I',count,1,17+index,0,count,count,131072,32,*([0]*8))
        records=[]
        for member in range(count):
            x=(member%8-4)*.65+math.sin(member*.13+index*.2)*.1;y=40+(member//8)*.65;z=-1500+math.cos(member*.3+index*.2)*.1
            records.append(struct.pack('<3fI3fI',x,y,z,member,1,0,0,member))
        data=header+b''.join(records);file=f'frame-{index:04d}.bin';(root/file).write_bytes(data)
        frames.append(dict(file=file,scene_frame=100+index,input_generation=17+index,time=index/60,dt=1/60,points=count,sha256=hashlib.sha256(data).hexdigest()))
    (root/'manifest.json').write_text(json.dumps(dict(schema=1,synthetic_test_sequence=True,complete=True,failure_reason='',requested_frames=16,record_bytes=32,header_bytes=64,frames=frames),indent=2)+'\n')

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('output',type=Path);a=p.parse_args();make(a.output)
