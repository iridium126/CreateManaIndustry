"""Export actual stage queries for OptiX build/refit experiments. Reject schema 1.

The captured radius is used verbatim. This is geometric replay, not a substitute
for the conservative continuation's complete physical validation.
"""
import argparse,hashlib,json,struct
from pathlib import Path
from r6_query_sequence import load_sequence

def export(source,target,stage='ordinary',queries=128):
    source=Path(source);target=Path(target);m=load_sequence(source)
    if m['schema']<2 or stage!='ordinary' and m['schema']<3:raise ValueError('actual query origin/radius/position stage was not captured')
    if target.exists():raise ValueError('refusing to overwrite sequence evidence')
    frames=[];topology=None;common=None
    for f in m['frames']:
        dome=stage!='ordinary';d=(source/(f['dome']['file'] if dome else f['file'])).read_bytes();stride=80 if dome else 64;points={};requests={}
        for at in range(64,len(d),stride):
            x,y,z,identity=struct.unpack_from('<3fI',d,at);points[identity]=(x,y,z)
            query=at+(48 if stage=='dome-solve' else 64) if dome else at+32
            qx,qy,qz,r=struct.unpack_from('<4f',d,query)
            valid=r>0 if dome else struct.unpack_from('<I',d,at+56)[0]==1
            if valid:requests[identity]=(qx,qy,qz,r)
        keys=sorted(points)
        if topology is None:topology=keys;common=set(requests)
        if keys!=topology:raise ValueError('topology changed; periodic rebuild/refit comparison is invalid')
        common.intersection_update(requests);frames.append((f,points,requests))
    chosen=sorted(common)[:queries]
    if not chosen:raise ValueError('no stable eligible actual query across every frame')
    index={identity:i for i,identity in enumerate(topology)};target.mkdir(parents=True);records=[];radius=0
    def write(name,value):
        (target/name).write_bytes(value);return hashlib.sha256(value).hexdigest()
    for i,(f,points,requests) in enumerate(frames):
        p=b''.join(struct.pack('<3fI',*points[identity],identity) for identity in topology);q=b''.join(struct.pack('<4f',*requests[identity]) for identity in chosen);owners=struct.pack('<'+'I'*len(chosen),*(index[identity] for identity in chosen));radius=max(radius,*(requests[x][3] for x in chosen))
        name=f'frame-{i:04d}';records.append(dict(name=name,file=name+'.bin',sha256=write(name+'.bin',p),requests=name+'-requests.bin',requests_sha256=write(name+'-requests.bin',q),owners=name+'-owners.bin',owners_sha256=write(name+'-owners.bin',owners),points=len(topology),scene_frame=f['scene_frame'],input_generation=f['input_generation']+(stage!='ordinary'),time=f['time'],dt=f['dt']))
    result=dict(schema=1,actual_requests=True,stage=stage,scope='captured actual query origin/radius at declared position stage; fixed common eligible identities; geometric build/refit only, no full physics or FPS',source_scope=m.get('scope','unavailable'),synthetic_fixture=m.get('synthetic_fixture','unavailable'),contiguous=True,periodic_rebuild_eligible=True,queries=len(chosen),query_identities=chosen,max_radius=radius,source_manifest_sha256=hashlib.sha256((source/'manifest.json').read_bytes()).hexdigest(),frames=records)
    (target/'inputs.json').write_text(json.dumps(result,indent=2)+'\n');return result
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('source',type=Path);p.add_argument('target',type=Path);p.add_argument('--stage',choices=('ordinary','dome-solve','dome-apply'),default='ordinary');p.add_argument('--queries',type=int,default=128);a=p.parse_args();r=export(a.source,a.target,a.stage,a.queries);print(json.dumps(dict(frames=len(r['frames']),queries=r['queries'],stage=r['stage'])))
