"""Validate bounded contiguous captures before any RT rebuild-period experiment.

Read-pool points are geometric query inputs, not locally recovered physics origins.
An invalid/missing frame invalidates the entire sequence; never bridge gaps.
"""
import argparse,hashlib,json,math,struct
from pathlib import Path

def load_sequence(directory):
    directory=Path(directory).resolve()
    manifest=json.loads((directory/'manifest.json').read_text())
    frames=manifest.get('frames',[]);count=manifest.get('requested_frames',0)
    schema=manifest.get('schema');stride=32 if schema==1 else 64
    if schema not in (1,2,3) or manifest.get('complete') is not True or manifest.get('failure_reason'):
        raise ValueError('uncommitted/invalid sequence')
    if not 16<=count<=128 or len(frames)!=count or manifest.get('record_bytes')!=stride or manifest.get('header_bytes')!=64:
        raise ValueError('invalid bounded sequence layout/length')
    previous=None
    for index,frame in enumerate(frames):
        if frame['file']!=f'frame-{index:04d}.bin':raise ValueError('missing/reordered frame')
        if previous is not None and (frame['scene_frame']!=previous['scene_frame']+1 or frame['input_generation']!=previous['input_generation']+1):
            raise ValueError('scene frame or input generation gap')
        data=(directory/frame['file']).read_bytes()
        if hashlib.sha256(data).hexdigest()!=frame['sha256']:raise ValueError('sequence checksum mismatch')
        if len(data)<64:raise ValueError('short sequence header')
        n,valid,lo,hi,live,models,capacity,record_stride=struct.unpack_from('<8I',data)
        if valid!=1 or record_stride!=stride or capacity!=131072 or n>capacity or n!=frame['points'] or len(data)!=64+n*stride or lo+(hi<<32)!=frame['input_generation']:
            raise ValueError('invalid frame header/generation/capacity')
        identities=set();slots=set()
        for offset in range(64,len(data),stride):
            x,y,z,identity,vx,vy,vz,slot=struct.unpack_from('<3fI3fI',data,offset)
            if not all(map(math.isfinite,(x,y,z,vx,vy,vz))) or identity>=131072 or slot>=live or identity in identities or slot in slots:
                raise ValueError('invalid/duplicate query member')
            identities.add(identity);slots.add(slot)
            if schema>=2:
                qx,qy,qz,radius,query_slot,query_identity,flags,stage=struct.unpack_from('<4f4I',data,offset+32)
                if flags not in (0,1,2):raise ValueError('invalid query flags')
                if flags and (query_slot!=slot or query_identity!=identity or stage!=1 or radius<=0 or not all(map(math.isfinite,(qx,qy,qz,radius)))):
                    raise ValueError('invalid actual query origin/radius/stage')
        if not math.isfinite(frame['time']) or not math.isfinite(frame['dt']) or frame['dt']<0:raise ValueError('invalid input clock')
        if schema==3:
            d=frame['dome'];payload=(directory/d['file']).read_bytes()
            if d['file']!=f'frame-{index:04d}-dome.bin' or hashlib.sha256(payload).hexdigest()!=d['sha256'] or len(payload)<64:raise ValueError('missing/corrupt Dome stage')
            n,active,lo,hi,live,_,cap,dome_stride=struct.unpack_from('<8I',payload)
            if cap!=131072 or dome_stride!=80 or d['record_bytes']!=80 or n!=d['points'] or n>cap or len(payload)!=64+n*80 or active!=int(d['present']) or lo+(hi<<32)!=frame['input_generation']+1 or d['candidate_generation']!=lo+(hi<<32) or (not active and n):raise ValueError('Dome stage generation/layout mismatch')
            identities=set();slots=set()
            for offset in range(64,len(payload),80):
                x,y,z,identity,vx,vy,vz,slot,phase,owner,valid,stage=struct.unpack_from('<3fI3fI4I',payload,offset)
                if valid!=1 or stage!=2 or identity>=cap or slot>=live or identity in identities or slot in slots or owner>4 or not all(map(math.isfinite,(x,y,z,vx,vy,vz))):raise ValueError('invalid/duplicate Dome candidate')
                identities.add(identity);slots.add(slot)
                for query in (48,64):
                    qx,qy,qz,radius=struct.unpack_from('<4f',payload,offset+query)
                    if not all(map(math.isfinite,(qx,qy,qz,radius))) or radius<0 or radius>0 and (not owner or phase not in (1,4,6)):raise ValueError('invalid Dome query boundary')
        previous=frame
    return manifest

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('directory',type=Path);args=parser.parse_args()
    result=load_sequence(args.directory)
    print(json.dumps(dict(valid=True,frames=len(result['frames']),scope='contiguous read-pool geometric queries only; no full physics/FPS qualification')))
