"""Export verified read-pool points for isolated GL/CUDA/RT geometry comparisons.

Never invent contiguous motion from separated schema-1/2 snapshots. Periodic
rebuild/refit is allowed only for validated sequences with fixed stable membership.
"""
import argparse,hashlib,json,struct
from pathlib import Path
from neighbor_reference import load_capture
from r6_query_sequence import load_sequence

def export(source,output,sequence=False):
    source,output=Path(source).resolve(),Path(output);output.mkdir(parents=True,exist_ok=False)
    frames=[];membership=None;synthetic=False
    inputs=[]
    if sequence:
        manifest=load_sequence(source)
        synthetic=manifest.get('synthetic_test_sequence',False)
        for frame in manifest['frames']:
            data=(source/frame['file']).read_bytes()
            rows=[]
            for at in range(64,len(data),manifest['record_bytes']):
                x,y,z,identity=struct.unpack_from('<3fI',data,at);rows.append((identity,x,y,z))
            inputs.append((frame['file'],rows,frame))
    else:
        for file in sorted(source.glob('*/manifest.json')):
            manifest,members=load_capture(file.parent)
            inputs.append((file.parent.name,[(m.identity,*m.position) for m in members],dict(scene_frame=manifest['frame'],input_generation=manifest['generation']-1)))
    if not inputs:raise ValueError('no committed input captures')
    fixed=True
    for index,(name,rows,metadata) in enumerate(inputs):
        rows.sort();identities=tuple(r[0] for r in rows)
        if membership is None:membership=identities
        else:fixed &= identities==membership
        data=b''.join(struct.pack('<3fI',x,y,z,identity) for identity,x,y,z in rows)
        file=f'points-{index:04d}.bin';(output/file).write_bytes(data)
        frames.append(dict(file=file,name=name,points=len(rows),sha256=hashlib.sha256(data).hexdigest(),**{k:metadata[k] for k in ('scene_frame','input_generation')}))
    result=dict(schema=1,scope='read-pool geometric queries only; not local recovery, full physics or client FPS',synthetic_input=synthetic,contiguous=sequence,fixed_membership=fixed,periodic_rebuild_eligible=sequence and fixed,source=str(source),frames=frames)
    (output/'inputs.json').write_text(json.dumps(result,indent=2)+'\n')
    return result

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('source',type=Path);p.add_argument('output',type=Path);p.add_argument('--sequence',action='store_true');a=p.parse_args();r=export(a.source,a.output,a.sequence);print('Exported',len(r['frames']),'frames; periodic rebuild eligible:',r['periodic_rebuild_eligible'])
