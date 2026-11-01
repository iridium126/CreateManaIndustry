"""Independent actual-query-boundary force oracle. Not a multi-frame physics qualification."""
import argparse,hashlib,json,math
from pathlib import Path
import numpy as np
from scipy.spatial import cKDTree

def validate(capture,run,variant):
 d=json.loads((capture/'manifest.json').read_text())
 def data(binding,dtype):
  desc=d['ssbo'][str(binding)];return np.memmap(capture/desc['file'],mode='r',dtype=dtype,offset=desc['start'])
 live=int(data(14,'<u4')[0]);pool=data(0,'<f4')[:live*16].reshape(-1,16);emit=data(5,'<f4').reshape(-1,80)
 storm=np.flatnonzero(emit[pool.view('<u4')[:,15],72]>.5);points=pool[storm,:3].astype(float)
 identities=(np.float32(pool[storm,3]+np.float32(.5)).astype(np.uint32)&262143)-np.uint32(1)
 tree=cKDTree(points);trace=np.fromfile(run/capture.name/variant/'queries.bin',dtype='<u4').reshape(-1,16);f=trace.view('<f4');queries=np.flatnonzero(trace[:,5]==1)
 max_error=0.;violations=[];total_neighbors=0;overlaps=0
 for identity in queries:
  origin=f[identity,:3];radius=float(f[identity,3]);self_index=int(trace[identity,4]);assert self_index<live
  ids=tree.query_ball_point(origin.astype(float),radius+max(1.e-5,float(np.max(np.abs(origin)))*1.e-6))
  idx=storm[ids];members=identities[ids];delta=np.float32(origin-pool[idx,:3]);squared=np.float32(np.float32(delta[:,0]*delta[:,0]+delta[:,1]*delta[:,1])+delta[:,2]*delta[:,2])
  mask=(idx!=self_index)&(squared<np.float32(radius*radius));delta=delta[mask];squared=squared[mask];members=members[mask]
  overlap=squared<=1.e-8;n_overlap=int(np.sum(overlap));overlaps+=n_overlap
  if n_overlap:
   # GLSL transcendental accuracy is implementation dependent. Preserve the original helper,
   # independently evaluated on the same GPU; do not replace its large-angle sin/cos by NumPy.
   directions=np.memmap(run/'separation-deltas.bin',mode='r',dtype='<f4').reshape(-1,4)
   delta[overlap]=np.float32(directions[np.minimum(identity,members[overlap]),:3]*np.where(identity<members[overlap],1.,-1.)[:,None]);squared[overlap]=np.sum(delta[overlap]*delta[overlap],axis=1)
  keep=(squared<np.float32(radius*radius))&(squared>1.e-8);delta=delta[keep];squared=squared[keep];length=np.sqrt(squared).astype(np.float32)
  terms=np.float32(delta*np.float32((np.float32(radius)-length)/np.float32(length*np.float32(radius)))[:,None]);expected=np.sum(terms.astype(float),axis=0);observed=f[identity,8:11].astype(float)
  n=len(terms);total_neighbors+=n;unit=2**-24;gamma=max(0,n-1)*unit/(1-max(0,n-1)*unit)
  bound=(gamma+32*unit)*np.sum(np.abs(terms.astype(float)),axis=0)+2.e-5
  error=np.abs(observed-expected);max_error=max(max_error,float(error.max()))
  if not np.all(np.isfinite(observed)) or np.any(error>bound):violations.append(dict(member=int(identity),neighbors=n,observed=observed.tolist(),reference=expected.tolist(),bound=bound.tolist()))
 baseline=np.fromfile(run/capture.name/'production/queries.bin',dtype='<u4').reshape(-1,16)
 origin_differences=int(np.sum(np.any(baseline[:,:6]!=trace[:,:6],axis=1)))
 output=np.fromfile(run/capture.name/variant/'output.bin',dtype='<f4').reshape(-1,16);counter=np.fromfile(run/capture.name/variant/'counter.bin',dtype='<u4');count=int(counter[0]);assert count<=len(output)
 result=dict(capture=capture.name,variant=variant,scope='actual separation boundary / FP32 force reduction',queries=len(queries),neighbors=total_neighbors,overlap_pairs=overlaps,max_absolute_force_error=max_error,origin_differences_from_production=origin_differences,violations=violations[:30],violation_count=len(violations),finite_output=bool(np.isfinite(output[:count,:14]).all()),output_live=count,passed=not violations and bool(np.isfinite(output[:count,:14]).all()),physics_qualification=False)
 return result
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('captures',type=Path);p.add_argument('run',type=Path);p.add_argument('variant');a=p.parse_args()
 results=[validate(c,a.run,a.variant) for c in sorted(a.captures.iterdir()) if (c/'manifest.json').exists()]
 (a.run/'query-validation.json').write_text(json.dumps(results,indent=2)+'\n');print(json.dumps([{k:v for k,v in r.items() if k!='violations'} for r in results],indent=2))
 if not all(r['passed'] for r in results):raise SystemExit(1)
