"""Exact sampled neighbour sets, independently enumerated at the actual query origin.

Unlike a force-sum comparison this detects cancelled missing pairs. Each query is
owned by one stable member invocation; raw admission count detects duplicates.
"""
import argparse,json
from pathlib import Path
import numpy as np
from scipy.spatial import cKDTree

def validate(capture,run,variant):
    manifest=json.loads((capture/'manifest.json').read_text())
    def data(binding,dtype):
        descriptor=manifest['ssbo'][str(binding)];return np.memmap(capture/descriptor['file'],mode='r',dtype=dtype,offset=descriptor['start'])
    live=int(data(14,'<u4')[0]);pool=data(0,'<f4')[:live*16].reshape(-1,16);emit=data(5,'<f4').reshape(-1,80)
    storm=np.flatnonzero(emit[pool.view('<u4')[:,15],72]>.5);points=pool[storm,:3].astype(float);members=(np.float32(pool[storm,3]+np.float32(.5)).astype(np.uint32)&262143)-np.uint32(1);assert len(np.unique(members))==len(members)
    tree=cKDTree(points);directory=run/capture.name/variant;trace=np.fromfile(directory/'queries.bin',dtype='<u4').reshape(-1,16);floats=trace.view('<f4');sets=np.memmap(directory/'adjacency.bin',mode='r',dtype='<u4',shape=(1024,4096));failures=[];queries=0;pairs=0
    for identity in np.flatnonzero(trace[:,5]==1):
        if identity&127:continue
        queries+=1;origin=floats[identity,:3];radius=floats[identity,3];self_index=trace[identity,4]
        near=tree.query_ball_point(origin.astype(float),float(radius)+max(1.e-5,float(np.max(np.abs(origin)))*1.e-6));indices=storm[near];delta=np.float32(origin-pool[indices,:3]);d2=np.float32(np.float32(delta[:,0]*delta[:,0]+delta[:,1]*delta[:,1])+delta[:,2]*delta[:,2]);expected=np.sort(members[near][(d2<np.float32(radius*radius))&(indices!=self_index)])
        observed=np.flatnonzero(np.unpackbits(sets[identity>>7].view('u1'),bitorder='little'));raw=int(trace[identity,6]);pairs+=len(expected)
        if not np.array_equal(expected,observed) or raw!=len(observed) or trace[identity,7]!=0:
            failures.append(dict(member=int(identity),expected=len(expected),observed=len(observed),raw_admissions=raw,missing=np.setdiff1d(expected,observed).tolist()[:30],extra=np.setdiff1d(observed,expected).tolist()[:30]))
    return dict(capture=capture.name,variant=variant,scope='exact candidate-set completeness and duplicate check at sampled actual separation queries (stable member divisible by 128)',queries=queries,relevant_pairs=pairs,failures=failures[:30],failure_count=len(failures),passed=not failures,physics_qualification=False)

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('captures',type=Path);parser.add_argument('run',type=Path);parser.add_argument('variant');args=parser.parse_args()
    results=[validate(c,args.run,args.variant) for c in sorted(args.captures.iterdir()) if (c/'manifest.json').exists()];(args.run/'candidate-sets.json').write_text(json.dumps(results,indent=2)+'\n');print(json.dumps(results,indent=2))
    if not all(r['passed'] for r in results):raise SystemExit(1)
