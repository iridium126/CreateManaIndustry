"""Continuous body-clearance certificate for decoded C2 trajectories.

Each interval is bounded by its relative chord and the certified acceleration
bound. Ambiguous pairs are bisected, never accepted from frame samples alone.
"""
import argparse,json,time
from pathlib import Path
import numpy as np
from scipy.spatial import cKDTree
from dome_resource_v4 import DomeResourceV4


def certify(path,step=.05):
 r=DomeResourceV4(path);m=r.metadata;minimum=100.;checked=0;refinements=0;started=time.time()
 def pairs(a,b,lo,hi,depth=0):
  nonlocal minimum,checked,refinements
  p=r.sample(a,lo)-r.sample(b,lo);q=r.sample(a,hi)-r.sample(b,hi);d=q-p
  u=np.clip(-np.sum(p*d,axis=-1)/np.maximum(np.sum(d*d,axis=-1),1e-20),0,1)
  chord=np.linalg.norm(p+u[:,None]*d,axis=-1);lower=chord-4.5*(hi-lo)**2-1e-8
  safe=lower>=.5;minimum=min(minimum,float(lower[safe].min(initial=100)));checked+=len(a)
  if safe.all():return
  a=a[~safe];b=b[~safe];lo=lo[~safe];hi=hi[~safe];mid=(lo+hi)*.5
  actual=np.linalg.norm(r.sample(a,mid)-r.sample(b,mid),axis=-1)
  if (actual<.5-1e-8).any():raise ValueError(f'Encoded body collision {actual.min()} at {mid[actual.argmin()]} members {a[actual.argmin()]}/{b[actual.argmin()]}')
  if depth>=16:raise ValueError('Unresolved continuous clearance')
  refinements+=len(a);pairs(a,b,lo,mid,depth+1);pairs(a,b,mid,hi,depth+1)
 for frame,t in enumerate(np.arange(0,m[:,2].max(),step)):
  ids=np.flatnonzero(m[:,2]>t);p=r.sample(ids,t)
  candidates=cKDTree(p).query_pairs(.5+12.02*step+.001,output_type='ndarray')
  if len(candidates):
   a,b=ids[candidates[:,0]],ids[candidates[:,1]];hi=np.minimum(t+step,np.minimum(m[a,2],m[b,2]));pairs(a,b,np.full(len(a),t),hi)
  if frame%100==0:print('space',t,'lower',minimum,'pairs',checked,'seconds',time.time()-started,flush=True)
 return dict(continuousMinimumBodySpacing=minimum,certifiedPairIntervals=checked,adaptivePairRefinements=refinements,accelerationBound=18,seconds=time.time()-started)

if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('asset');p.add_argument('--report',default='build/dome-v14/space-certificate.json');args=p.parse_args();result=certify(args.asset);Path(args.report).write_text(json.dumps(result,indent=2));print(result,flush=True)
